package com.stratio.decision.functions.engine;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.generic.GenericData;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.beanutils.PropertyUtils;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.siddhi.core.SiddhiManager;
import org.wso2.siddhi.core.event.Event;

import com.stratio.decision.commons.constants.InternalTopic;
import com.stratio.decision.commons.messages.ColumnNameTypeValue;
import com.stratio.decision.commons.messages.StratioStreamingMessage;
import com.stratio.decision.drools.DroolsConnectionContainer;
import com.stratio.decision.drools.DroolsInstace;
import com.stratio.decision.drools.configuration.DroolsConfigurationGroupBean;
import com.stratio.decision.drools.sessions.DroolsSession;
import com.stratio.decision.drools.sessions.DroolsStatefulSession;
import com.stratio.decision.drools.sessions.DroolsStatelessSession;
import com.stratio.decision.drools.sessions.Results;
import com.stratio.decision.serializer.Serializer;
import com.stratio.decision.service.StreamOperationServiceWithoutMetrics;

import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;

/**
 * Created by josepablofernandez on 2/12/15.
 */
public class DroolsEngineAction extends BaseEngineAction {

    private static final Logger log = LoggerFactory.getLogger(DroolsEngineAction.class);

    private DroolsConnectionContainer droolsConnectionContainer;
    private List<String> groups;

    public DroolsEngineAction(DroolsConnectionContainer droolsConnectionContainer, Object[] actionParameters,
            SiddhiManager siddhiManager,  StreamOperationServiceWithoutMetrics streamOperationService) {

        super(actionParameters, siddhiManager, streamOperationService);

        this.droolsConnectionContainer = droolsConnectionContainer;
        this.groups = new ArrayList<>();

        for (Object actionParameter : actionParameters) {

            groups.add((String) actionParameter);
        }

    }


//    public DroolsEngineAction(DroolsConnectionContainer droolsConnectionContainer, Object[] actionParameters,
//            SiddhiManager siddhiManager) {
//
//        super(actionParameters, siddhiManager);
//
//        this.droolsConnectionContainer = droolsConnectionContainer;
//        this.groups = new ArrayList<>();
//
//        for (Object actionParameter : actionParameters) {
//
//            groups.add((String) actionParameter);
//        }
//
//    }


    private List<Map<String, Object>> formatInputEvents(Event[] events){

        List<Map<String, Object>> inputList = new ArrayList<>();

        for (Event event : events) {

            Map<String, Object> inputMap = new HashMap<>();

            StratioStreamingMessage messageObject = javaToSiddhiSerializer.deserialize(event);

            for (ColumnNameTypeValue column : messageObject.getColumns()) {

                inputMap.put(column.getColumn(), column.getValue());
            }

            inputList.add(inputMap);

        }

        return inputList;

    }

    private List<Map<String, Object>> formatDroolsResults(Results results){

        List<Map<String, Object>> outputList = new ArrayList<>();

        for (Object singleResult : results.getResults()) {

            Map<String, Object> outputMap = null;

            try {

                Object propertyObject = PropertyUtils.getProperty(singleResult, "result");
                //outputMap = PropertyUtils.describe(singleResult);
                outputMap = PropertyUtils.describe(propertyObject);


            } catch (IllegalAccessException|InvocationTargetException|NoSuchMethodException e) {
                //e.printStackTrace();
                // TODO log exception
            }

            outputList.add(outputMap);

        }

        return outputList;

    }


    @Override
    public void execute(String streamName, Event[] inEvents) {

        if (log.isDebugEnabled()) {

            log.debug("Firing rules for stream {}", streamName);
        }

        DroolsInstace instance;
        DroolsSession session = null;

        for (String groupName : groups){

            instance = droolsConnectionContainer.getGroupContainer(groupName);
            session = instance.getSession();

            if (instance == null) {

                if (log.isDebugEnabled()) {
                    log.debug("EXECUTED for groupName {}", groupName);
                }

                return;

            }


            if (session != null) {

                List<Map<String, Object>> inputData = this.formatInputEvents(inEvents);
                Results results = session.fireRules(inputData);

                if (results.getResults().size()== 0) {

                    log.info("No Results from Drool. Check your rules!!");

                } else {

                    List<Map<String, Object>> formattedResults = this.formatDroolsResults(results);

                    // TODO Read from api/shell the output stream name
                    Boolean sendToCep = true;
                    String cepOutputStreamName = "drools_result";

                    if (sendToCep) {
                        this.handleCepRedirection(cepOutputStreamName, formattedResults);
                    }
                }
            } else {

                if (log.isDebugEnabled()) {

                    log.debug("No Drools Session instance for stream {}", streamName);
                }

            }

        }

        if (log.isDebugEnabled()) {

            log.debug("Finished rules processing for stream {}", streamName);
        }

    }


}