package com.calm.Executor;

import com.calm.CalmHelpers.Application;
import com.calm.CalmHelpers.Blueprint;
import com.calm.CalmHelpers.Project;
import com.calm.Interface.Rest;
import org.json.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.io.PrintStream;
import java.util.HashMap;

import hudson.EnvVars;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;
import java.util.List;

public class CalmExecutor {
    private String prismCentralIp, userName, password, blueprintName, applicationName, applicationProfileName, actionName,
            projectName, runTimeVariables;
    private  boolean waitForLaunchSuccess, verifyCertificate;
    private final PrintStream logger;


    public CalmExecutor(String prismCentralIp, String userName, String password, String projectName, String blueprintName,
                        String appProfileName, String actionName, String runtimeVariables, String applicationName,
                        boolean waitForLaunchSuccess, PrintStream logger, boolean verifyCertificate) {
        this.prismCentralIp = prismCentralIp;
        this.userName = userName;
        this.password = password;
        this.projectName = projectName;
        this.blueprintName = blueprintName;
        this.applicationName = applicationName;
        this.applicationProfileName = appProfileName;
        this.actionName = actionName;
        this.runTimeVariables = runtimeVariables;
        this.waitForLaunchSuccess = waitForLaunchSuccess;
        this.logger = logger;
        this.verifyCertificate = verifyCertificate;
    }

    public CalmExecutor(String prismCentralIp, String userName, String password, String applicationName,
                        String actionName, String runTimeVariables, PrintStream logger, boolean verifyCertificate){
        this.prismCentralIp = prismCentralIp;
        this.userName = userName;
        this.password = password;
        this.applicationName = applicationName;
        this.actionName = actionName;
        this.runTimeVariables = runTimeVariables;
        this.logger = logger;
        this.verifyCertificate = verifyCertificate;
    }

    public void launchBlueprint()throws Exception{
        Rest rest = new Rest(this.prismCentralIp, this.userName, this.password, this.verifyCertificate);
        Blueprint blueprintHelper = Blueprint.getInstance(rest);
        Application applicationHelper = Application.getInstance(rest);
        String applicationUuid = null;
        logger.println(" Selected Project: " +  this.projectName);
        logger.println(" Selected Blueprint: "+  this.blueprintName);
        logger.println(" Selected Profile: " +  this.applicationProfileName);
        logger.println(" Application Name: " + this.applicationName);

        JSONObject blueprintJson = blueprintHelper.getBlueprintSpec(this.blueprintName, this.applicationProfileName,
                this.runTimeVariables, this.applicationName);
        String blueprintUuid = blueprintJson.getJSONObject("metadata").getString("uuid");
        JSONObject response;
        try{
            response = blueprintHelper.launchBlueprint(blueprintUuid, blueprintJson);
        }
        catch (Exception e){
            throw  new Exception("Blueprint launch failed with error: \n" + e.getMessage());
        }

        if(this.waitForLaunchSuccess){
            logger.println(" ");
            logger.println("Waiting for application " + this.applicationName  + " launch to complete : ");
        }
        else{
            logger.println(" ");
            logger.println("Blueprint launched sucessfully");
        }

        if (this.waitForLaunchSuccess) {
            String requestId = response.getJSONObject("status").getString("request_id");
            applicationUuid = applicationHelper.getApplicationUuidByRequestId(blueprintUuid, requestId);
            Application.applicationNameUuidPair.put(applicationName, applicationUuid);
            String applicationStatus = applicationHelper.waitForApplicationToGoIntoSuccessState(this.applicationName, applicationUuid, "running", logger);
            if (!applicationStatus.equals("running")) {
                applicationHelper.taskOutput(applicationUuid, "action_create", logger);
                throw new Exception("Application " + this.applicationName + " has failed with an error, please have a look into this app in your PC");

            }
            else{
                applicationHelper.taskOutput(applicationUuid, "action_create", logger);
                logger.println(" Application " + this.applicationName + " is " + applicationStatus);
            }
        }
        getAppDetails(this.applicationName);
    }

    public void runAppAction()throws Exception{
        Rest rest =  new Rest(prismCentralIp, userName, password, this.verifyCertificate);
        Application applicationHelper = Application.getInstance(rest);
        String  appUuid = applicationHelper.getAppUUID(this.applicationName);
        JSONObject appResponse = applicationHelper.getApplicationDetails(appUuid);
        String projectName = appResponse.getJSONObject("metadata").getJSONObject("project_reference").getString("name");
        String projectUUID = appResponse.getJSONObject("metadata").getJSONObject("project_reference").getString("uuid");
        String spec = "{\n" +
                "                \"api_version\": \"3.0\",\n" +
                "                \"metadata\": {\n" +
                "                    \"kind\": \"app\",\n" +
                "                    \"spec_version\": 5,\n" +
                "                    \"project_reference\": {\n" +
                "                        \"kind\": \"project\",\n" +
                "                        \"name\":\"" + projectName + "\",\n" +
                "                        \"uuid\":\"" + projectUUID + "\"\n" +
                "                    }\n" +
                "                },\n" +
                "                \"spec\": {\n" +
                "                    \"target_uuid\":\"" + appUuid + "\",\n" +
                "                    \"target_kind\": \"Application\",\n" +
                "                    \"args\": []\n" +
                "                }\n" +
                "            }";
        JSONObject actionSpec = new JSONObject(spec);
        actionSpec = applicationHelper.patchAppProfileActionVariables(actionSpec, this.applicationName, this.actionName, this.runTimeVariables, logger);
        JSONObject actionRunResponse;
        try{
            actionRunResponse = applicationHelper.runAction(this.applicationName, this.actionName, actionSpec);
        }
        catch (Exception e){
            logger.println("Action run failed with :\n" + e.getMessage());
            throw new Exception("Action run failed "+ e.getMessage());
        }

        String runlogUuid = actionRunResponse.getJSONObject("status").getString("runlog_uuid");
        String actionStatus = applicationHelper.waitForActionToComplete(this.applicationName, runlogUuid, this.logger);
        if (!actionStatus.equals("SUCCESS")) {
            //TODO : fetch failed task logs
            applicationHelper.taskOutput(appUuid, this.actionName, logger);
            throw new Exception("Application Action " + this.actionName + " has failed with an error, please have a look into this app in your PC");
        }
        else{
            applicationHelper.taskOutput(appUuid, this.actionName, logger);
            logger.println(" Application Action " + this.actionName + " is " + actionStatus);
        }
    }

    public void createGlobalEnvironmentVariables(String key, String value)throws Exception{

        Jenkins instance = Jenkins.getInstance();

        DescribableList<NodeProperty<?>, NodePropertyDescriptor> globalNodeProperties = instance.getGlobalNodeProperties();
        List<EnvironmentVariablesNodeProperty> envVarsNodePropertyList = globalNodeProperties.getAll(EnvironmentVariablesNodeProperty.class);

        EnvironmentVariablesNodeProperty newEnvVarsNodeProperty = null;
        EnvVars envVars = null;

        if ( envVarsNodePropertyList == null || envVarsNodePropertyList.size() == 0 ) {
            newEnvVarsNodeProperty = new hudson.slaves.EnvironmentVariablesNodeProperty();
            globalNodeProperties.add(newEnvVarsNodeProperty);
            envVars = newEnvVarsNodeProperty.getEnvVars();
        } else {
            envVars = envVarsNodePropertyList.get(0).getEnvVars();
        }
        envVars.put(key, value);
        instance.save();

    }

    public void getAppDetails (String applicationName)throws Exception{
        Rest rest =  new Rest(prismCentralIp, userName, password, this.verifyCertificate);
        Application applicationHelper = Application.getInstance(rest);
        HashMap<String, String> appDetails = applicationHelper.applicationDetails(applicationName);
        createGlobalEnvironmentVariables("CalmServicesIP",(appDetails).toString());
    }

}
