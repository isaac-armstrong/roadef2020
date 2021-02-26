
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gurobi.*;
import lombok.Data;
import org.apache.commons.io.FilenameUtils;
import org.supercsv.cellprocessor.constraint.NotNull;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.io.CsvBeanWriter;
import org.supercsv.io.ICsvBeanWriter;
import org.supercsv.prefs.CsvPreference;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

public class App {
    public static void robustBoxModel(RobustModelConfiguration robustModelConfiguration, double timeLimitSeconds) throws GRBException, IOException {

        //TODO add check to make sure uncertainty budget is >=0 and <= num
        // risk parameters
        GRBEnv env = new GRBEnv(true);
        env.set("logfile", robustModelConfiguration.getDirectoryPath() +
                "solver" +
                ".log");
        env.start();

        GRBModel model = new GRBModel(env);

        // TODO Timelimit should go to a dedicated Solver configuration object
        model.set(GRB.DoubleParam.TimeLimit, timeLimitSeconds);

        // Lookup variables by intervention and starttime. Assumes 0-indexed
        // start time and construction assumes that start times are
        // contiguous with maximum arraylist size of tmax. "Interventions
        // are contiguous, and terminate before the end of the schedule."
        Map<String, ArrayList<GRBVar>> interventionLookup = new HashMap<>();

        // Given a timestep, which interventions are active? Given the
        // intervention, we can choose a START time step and get the
        // variable. Why this form? We frequently need to look up a
        // coefficeint to go with the variable and that means knowing the
        // start time of the intervention variable, not just having the
        // intervention variable on hand. These start times may not start at
        // 0 each time which rules out using an ArrayList of variables given
        // an intervention.
        Map<Integer, Map<String, Map<Integer,GRBVar>>> activeLookup =
                new HashMap<>();


        // Declare variables
        declareVariables(robustModelConfiguration.getConfig(), model, interventionLookup, activeLookup);

        // Entries need to be scheduled
        addInterventionCoveringConstraints(model, interventionLookup);

        // Upper and lower bound constraints
        addResourceBoundConstraints(robustModelConfiguration.getConfig(), model, activeLookup);

        // Exclusions
        addExclusionConstraints(robustModelConfiguration.getConfig(), model, activeLookup);

        addRobustObjective(robustModelConfiguration.getConfig(),model, robustModelConfiguration.getUncertaintyBudget(),activeLookup);

        // Optimize model
        model.optimize();

        writeModelOutput(model, robustModelConfiguration.getDirectoryPath());

        ObjectMapper mapper = new ObjectMapper();
        // Serialize the object into JSON
        var modelConfigFilepath = robustModelConfiguration.getDirectoryPath() +
                "model_configuration" +
                ".json";
        mapper.writeValue(new File(modelConfigFilepath), robustModelConfiguration);

        writeOfficialSolution(interventionLookup,
                robustModelConfiguration.getDirectoryPath());

        // Dispose of model and environment
        model.dispose();
        env.dispose();

    }

    public static void twoStageEqualProbabilityModel(ProblemConfiguration config,String prefix) throws GRBException, IOException {

        GRBEnv env = new GRBEnv(true);
        env.set("logfile",prefix+".log");
        env.start();

        GRBModel model = new GRBModel(env);
        // Lookup variables by intervention and starttime. Assumes 0-indexed
        // start time and construction assumes that start times are
        // contiguous with maximum arraylist size of tmax. "Interventions
        // are contiguous, and terminate before the end of the schedule."
        Map<String, ArrayList<GRBVar>> interventionLookup = new HashMap<>();

        // Given a timestep, which interventions are active? Given the
        // intervention, we can choose a START time step and get the
        // variable. Why this form? We frequently need to look up a
        // coefficeint to go with the variable and that means knowing the
        // start time of the intervention variable, not just having the
        // intervention variable on hand. These start times may not start at
        // 0 each time which rules out using an ArrayList of variables given
        // an intervention.
        Map<Integer, Map<String, Map<Integer,GRBVar>>> activeLookup =
                new HashMap<>();


        // Declare variables
        declareVariables(config, model, interventionLookup, activeLookup);

        // Entries need to be scheduled
        addInterventionCoveringConstraints(model, interventionLookup);

        // Upper and lower bound constraints
        addResourceBoundConstraints(config, model, activeLookup);

        // Exclusions
        addExclusionConstraints(config, model, activeLookup);

        GRBLinExpr objectiveFunction = createEqualProbabilityObjectiveFunction(config,
                activeLookup);

        model.setObjective(objectiveFunction, GRB.MINIMIZE);

        // Optimize model
        model.optimize();

        //System.out.println("Obj: " + model.get(GRB.DoubleAttr.ObjVal));

        // Human readable
        model.write(prefix+".lp");

        //Pickup where you left off
        model.write(prefix+".mst");

        //TODO return solution object that can be written in main? solution
        // object with multiple interfaces? writeOfficialSolution?
        // Include config input name in output
        model.write(prefix+".sol");

        // Includes runtime information
        model.write(prefix+".json");

        model.write(prefix+".prm");
        writeOfficialSolution(interventionLookup, prefix);

        // Dispose of model and environment
        model.dispose();
        env.dispose();

    }

    private static void writeOfficialSolution(Map<String, ArrayList<GRBVar>> interventionLookup, String directory) throws GRBException, IOException {
        //Create sparse solution map AKA intervention -> start times
        // Could enforce uniqueness...
        var solution = new ArrayList<>();
        for (var interventionMap :
                interventionLookup.entrySet()) {
            var interventionName = interventionMap.getKey();
            var interventionDecisions = interventionMap.getValue();

            // TODO Use while loop and stop at first nonzero. Consider
            //  making sure that only 1 start time is used for the
            //  intervention as you could be writing over it right now
            for (int startTime = 0; startTime < interventionDecisions.size(); startTime++) {
                var decision = interventionDecisions.get(startTime);
                var decisionValue = decision.get(GRB.DoubleAttr.X);

                // Adjust for 0 indexing we did on the way into the program
                // in the configuration file
                var officalStartTime = startTime + 1;

                if (decisionValue > 0) {
                    solution.add(new InterventionStart(interventionName,
                            officalStartTime));
                }

            }
        }

        var solutionName =
                directory + "official_solution.txt";

        ICsvBeanWriter beanWriter = null;
        try {
            CsvPreference SPACE_DELIMITED = new CsvPreference.Builder('"',
                    ' ', "\n").build();
            beanWriter = new CsvBeanWriter(new FileWriter(solutionName),
                    SPACE_DELIMITED);

            // the header elements are used to map the bean values to each column (names must match)
            final String[] header = new String[] {"intervention","startTime"};
            var processors =new CellProcessor[] {
                    new NotNull(), // intervention
                    new NotNull(), // startTime
                    //new LMinMax(0L, LMinMax.MAX_LONG)
            };

            // write the header
            //beanWriter.writeHeader(header);

            // write the beans
            for( var interventionStart : solution ) {
                beanWriter.write(interventionStart, header, processors);
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if( beanWriter != null ) {
                beanWriter.close();
            }
        }
    }

    @Data
    public static class InterventionStart{
        public final String intervention;
        public final int startTime;
    }

    /**
     * TODO capture cplex log without overwrite, capture start and end time,
     * consider retries, produce solution object, write to csv, realtime log
     * to disk in case it terminates early. Timestamped log.
     * @param config
     */
    public static void deterministicModel(ProblemConfiguration config,
                                          String directoryPath,
                                          double timeLimitSeconds) throws GRBException, IOException {

        GRBEnv env = new GRBEnv(true);
        env.set("logfile",directoryPath+"solver.log");
        env.start();

        GRBModel model = new GRBModel(env);

        //TODO This should really be in a dedicated configuration object
        model.set(GRB.DoubleParam.TimeLimit,timeLimitSeconds);
        // Lookup variables by intervention and starttime. Assumes 0-indexed
        // start time and construction assumes that start times are
        // contiguous with maximum arraylist size of tmax. "Interventions
        // are contiguous, and terminate before the end of the schedule."
        Map<String, ArrayList<GRBVar>> interventionLookup = new HashMap<>();

        // Given a timestep, which interventions are active? Given the
        // intervention, we can choose a START time step and get the
        // variable. Why this form? We frequently need to look up a
        // coefficeint to go with the variable and that means knowing the
        // start time of the intervention variable, not just having the
        // intervention variable on hand. These start times may not start at
        // 0 each time which rules out using an ArrayList of variables given
        // an intervention.
        Map<Integer, Map<String, Map<Integer,GRBVar>>> activeLookup =
                new HashMap<>();


        // Declare variables
        declareVariables(config, model, interventionLookup, activeLookup);

        // Entries need to be scheduled
        addInterventionCoveringConstraints(model, interventionLookup);

        // Upper and lower bound constraints
        addResourceBoundConstraints(config, model, activeLookup);

        // Exclusions
        addExclusionConstraints(config, model, activeLookup);

        GRBLinExpr objectiveFunction = createExpectedObjectiveFunction(config,
                activeLookup);

        model.setObjective(objectiveFunction, GRB.MINIMIZE);

        // Optimize model
        model.optimize();

        String timeStamp = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss").format(new Date());
        //System.out.println("Obj: " + model.get(GRB.DoubleAttr.ObjVal));

        writeModelOutput(model,directoryPath);

        // TODO This feels ad-hoc andside effects should be fatored out of
        //  direct modelcode
        ObjectMapper mapper = new ObjectMapper();
        // Serialize the object into JSON
        var modelConfigFilepath = directoryPath +
                "model_configuration" +
                ".json";
        mapper.writeValue(new File(modelConfigFilepath), config);

        writeOfficialSolution(interventionLookup, directoryPath);

        // Dispose of model and environment
        model.dispose();
        env.dispose();


    }

    /**
     * Runtime must be acquired immediately after using model.optimize due
     * to an ongoing bug with Gurobi.
     * @param model
     * @param directory
     * @throws GRBException
     * @throws IOException
     */
    private static void writeModelOutput(GRBModel model,String directory) throws GRBException, IOException {

        // Known bug in Gurobi runtime
        // https://support.gurobi.com/hc/en-us/articles/360043084272-Why-is-the-Runtime-attribute-0-
        double runtime = model.get(GRB.DoubleAttr.Runtime);
        double iterCount = model.get(GRB.DoubleAttr.IterCount);
        var statsMap = new HashMap<>();
        statsMap.put("runtime",runtime);
        statsMap.put("iterCount",iterCount);
        ObjectMapper mapper = new ObjectMapper();
        // Serialize the object into JSON
        var statsFilepath = directory +"stats.json";
        mapper.writeValue(new File(statsFilepath), statsMap);

        // Human readable
        var lpFilepath = directory + "model.lp";
        model.write(lpFilepath);

        //Pickup where you left off
        var mstFilepath = directory + "model.mst";
        model.write(mstFilepath);

        //TODO return solution object that can be written in main? solution
        // object with multiple interfaces? writeOfficialSolution?
        // Include config input name in output
        var solFilepath = directory + "model.sol";
        model.write(solFilepath);

        // Includes runtime information
        var jsonFilePath = directory +"model.json";
        model.write(jsonFilePath);

        var prmFilePath = directory +"model.prm";
        model.write(prmFilePath);

    }

    private static void declareVariables(ProblemConfiguration config, GRBModel model, Map<String, ArrayList<GRBVar>> interventionLookup, Map<Integer, Map<String, Map<Integer, GRBVar>>> activeLookup) throws GRBException {
        for (Map.Entry<String, ProblemConfiguration.Intervention> intervention_entry :
                config.getInterventions().entrySet()) {

            String interventionName = intervention_entry.getKey();
            int maxStartTime = intervention_entry.getValue().getTmax();
            for (int startTime = 0; startTime <= maxStartTime; startTime++) {

                String interventionVariableName =
                        interventionName + "_" + startTime;

                GRBVar intervention = model.addVar(0.0, 1.0, 0.0, GRB.BINARY,
                        interventionVariableName);

                if (!interventionLookup.containsKey(interventionName)){
                    interventionLookup.put(interventionName,
                            new ArrayList<>());
                }
                interventionLookup.get(interventionName).add(intervention);

                int delta =
                        intervention_entry.getValue().getDelta().get(startTime);

                int windowEnd = startTime + delta;

                for (int activeTimestep = startTime; activeTimestep < windowEnd; activeTimestep++) {

                    // What variables are associated wit ha given intervention?

                    // TODO Guarantee by construction or all at once to
                    //  otherwise avoid branching?
                    if (!activeLookup.containsKey(activeTimestep)){
                        activeLookup.put(activeTimestep,new HashMap<>());
                    }
                    var interventionMap = activeLookup.get(activeTimestep);

                    // Collection of variables of for the intervention
                    if (!interventionMap.containsKey(interventionName)){
                        interventionMap.put(interventionName,new HashMap<>());
                    }
                    var interventionVars = interventionMap.get(interventionName);
                    interventionVars.put(startTime,intervention);

                }

            }


        }
    }

    private static void addInterventionCoveringConstraints(GRBModel model, Map<String, ArrayList<GRBVar>> interventionLookup) throws GRBException {
        for (Map.Entry<String, ArrayList<GRBVar>> interventionEntry :
                interventionLookup.entrySet()) {

            GRBLinExpr expr = new GRBLinExpr();
            var constraintName =
                    "cover_intervention_" + interventionEntry.getKey();

            interventionEntry.getValue().forEach(intervention -> expr.addTerm(1.0, intervention));

            model.addConstr(expr, GRB.EQUAL, 1, constraintName);

        }
    }

    private static void addResourceBoundConstraints(ProblemConfiguration config, GRBModel model, Map<Integer, Map<String, Map<Integer, GRBVar>>> activeLookup) throws GRBException {
        for (Map.Entry<String, Map<String, ArrayList<Integer>>> resourceEntry:
        config.getResources().entrySet()){

            var resourceName = resourceEntry.getKey();

            // We are interested in the interventions that are active at
            // this time step NOT just those that started at this timestep.
            for (int activeTimeStep = 0; activeTimeStep <= config.getT(); activeTimeStep++) {

                GRBLinExpr totalScheduledWorkload = new GRBLinExpr();

                // Which intervention variables could possibly be active at
                // this time? They may not actually be active.
                var activeInterventionVar =activeLookup.get(activeTimeStep);

                // Given the resource and active timestep of interest, we
                // can create the linear expresion composed of
                // decisions/interventions that could be active during this
                // timestep
                for (Map.Entry<String, Map<Integer, GRBVar>> activeInterventionEntry:
                     activeInterventionVar.entrySet()) {

                    var interventionName = activeInterventionEntry.getKey();
                    var activeVar = activeInterventionEntry.getValue();

                    if (config.hasResourceWorkload(interventionName,resourceName)){

                        for (Map.Entry<Integer, GRBVar> startTimeMap :
                                activeVar.entrySet()) {

                            // TODO consider checking both outside of the loop
                            if (config.hasResourceWorkloadActiveStep(interventionName,resourceName,activeTimeStep)){
                                int startTime = startTimeMap.getKey();

                                var workload = config.getWorkload(interventionName,
                                        resourceName, startTime, activeTimeStep);

                                // It's possible  for a workload not to be
                                // present. There is no need to add that to
                                // the constraint if it's just 0.
                                if (workload!=0){
                                    var intervention = startTimeMap.getValue();

                                    totalScheduledWorkload.addTerm(workload,intervention);
                                }

                            }


                        }

                    }

                }

                var lowerBound = config.getResourceMin(resourceName,activeTimeStep);
                var lowerBoundConstraintName = "lower_"+resourceName + "_" + activeTimeStep;
                model.addConstr(totalScheduledWorkload, GRB.GREATER_EQUAL,
                        lowerBound,lowerBoundConstraintName);

                var upperBound = config.getResourceMax(resourceName,activeTimeStep);
                var upperBoundConstraintName = "upper_"+resourceName + "_" + activeTimeStep;
                model.addConstr(totalScheduledWorkload,GRB.LESS_EQUAL,
                        upperBound,upperBoundConstraintName);

            }

        }
    }

    /**
     * TODO Streams such that you can create one exclusion at a time would
     * be great.
     * @param config
     * @param model
     * @param activeLookup
     * @throws GRBException
     */
    private static void addExclusionConstraints(ProblemConfiguration config, GRBModel model, Map<Integer, Map<String, Map<Integer, GRBVar>>> activeLookup) throws GRBException {
        for (Map.Entry<String, ArrayList<String>> exclusionEntry :
                config.getExclusions().entrySet()) {
            var season = exclusionEntry.getValue().get(2);
            var seasonTimeSteps = config.getSeasons().get(season);
            var firstInterventionName = exclusionEntry.getValue().get(0);
            var secondInterventionName = exclusionEntry.getValue().get(1);

            for (int activeTimeStep :
                    seasonTimeSteps) {

                var activeInterventions = activeLookup.get(activeTimeStep);
                // If both in this map, then add the exclusion constraint
                // Otherwise, no need to add this constraint

                var haveSharedTimeStep =
                        activeInterventions.containsKey(firstInterventionName) && activeInterventions.containsKey(secondInterventionName);
                if (haveSharedTimeStep){
                    var firstInterventionMap =
                            activeInterventions.get(firstInterventionName);
                    var secondInterventionMap =
                            activeInterventions.get(secondInterventionName);

                    for (Map.Entry<Integer,GRBVar> firstInteventionEntry:
                         firstInterventionMap.entrySet()) {
                        for (Map.Entry<Integer,GRBVar> secondInterventionEntry:
                             secondInterventionMap.entrySet()) {

                            var firstIntervention =
                                    firstInteventionEntry.getValue();
                            var secondIntervention =
                                    secondInterventionEntry.getValue();

                            var exclusionName = exclusionEntry.getKey();
                            var constraintName =
                                    exclusionName + "_" + activeTimeStep;

                            GRBLinExpr rhs = new GRBLinExpr();
                            rhs.addTerm(-1,secondIntervention);
                            rhs.addConstant(1);

                            model.addConstr(firstIntervention, GRB.LESS_EQUAL
                                    ,rhs,constraintName);

                        }

                    }
                }

            }

        }
    }

    private static GRBLinExpr createEqualProbabilityObjectiveFunction(ProblemConfiguration config,
                                                                      Map<Integer,Map<String,Map<Integer,GRBVar>>> activeLookup){
        // Find out which interventions are active at each timestep and find
        // the risk of being active at that timestep.
        GRBLinExpr objectiveFunction = new GRBLinExpr();
        for (var activeEntry :
                activeLookup.entrySet()) {

            var activeTimeStep = activeEntry.getKey();

            for (var interventionEntry :
                    activeEntry.getValue().entrySet()) {
                var interventionName = interventionEntry.getKey();

                for (var startTimeEntry :
                        interventionEntry.getValue().entrySet()) {
                    var startTime = startTimeEntry.getKey();
                    var intervention = startTimeEntry.getValue();

                    var scenarioRisks =
                            config.getRiskScenarios(interventionName,
                                    startTime,activeTimeStep);
                    for (var scenarioRisk :
                            scenarioRisks) {

                        objectiveFunction.addTerm(scenarioRisk,intervention);
                    }

                }

            }

        }
        return objectiveFunction;
    }

    private static void addRobustObjective(ProblemConfiguration config
            , GRBModel model, double uncertainty_budget,
                                           Map<Integer, Map<String,
                                                   Map<Integer, GRBVar>>> activeLookup) throws GRBException {

        // TODO I think it should be negative at all times
        GRBVar totalRisk = model.addVar(-1 * GRB.INFINITY,GRB.INFINITY,0,
                GRB.CONTINUOUS,
                "total_risk");

        GRBVar zVar = model.addVar(0,GRB.INFINITY,0,GRB.CONTINUOUS,"z");

        GRBLinExpr qSum = new GRBLinExpr();


        // TODO consider adding the expected value objective function loop
        //  to this or composing via streams
        for (var activeEntry :
                activeLookup.entrySet()) {

            var activeTimeStep = activeEntry.getKey();

            for (var interventionEntry :
                    activeEntry.getValue().entrySet()) {
                var interventionName = interventionEntry.getKey();

                for (var startTimeEntry :
                        interventionEntry.getValue().entrySet()) {
                    var startTime = startTimeEntry.getKey();
                    var intervention = startTimeEntry.getValue();

                    var uncertaintyIndexName =
                            interventionName + "_" + activeTimeStep + "_"+startTime;
                    var qVarName =
                            "q_" + uncertaintyIndexName;
                    var qVar= model.addVar(0,GRB.INFINITY,0,GRB.CONTINUOUS,
                            qVarName);

                    qSum.addTerm(1,qVar);

                    var scenarioMeanRisk =
                            config.getScenarioMeanRisk(interventionName,
                                    startTime,activeTimeStep);

                    var uncertainRiskConstraintLHS = new GRBLinExpr();
                    uncertainRiskConstraintLHS.addTerm(1,zVar);
                    uncertainRiskConstraintLHS.addTerm(1,qVar);

                    var uncertainRiskConstraintRHS = new GRBLinExpr();
                    var halfWindowSize =
                            config.getScenarioStandardDeviationRisk(interventionName,startTime,activeTimeStep);
                    uncertainRiskConstraintRHS.addTerm(halfWindowSize,intervention);

                    model.addConstr(uncertainRiskConstraintLHS,
                            GRB.GREATER_EQUAL,uncertainRiskConstraintRHS,
                            uncertaintyIndexName);

                }

            }

        }

        GRBLinExpr expectedDeterministicObjective =
                createExpectedObjectiveFunction(config,activeLookup);

        GRBLinExpr robustObjectiveConstraintLHS = new GRBLinExpr();
        robustObjectiveConstraintLHS.addTerm(1,totalRisk);
        robustObjectiveConstraintLHS.add(expectedDeterministicObjective);
        robustObjectiveConstraintLHS.addTerm(uncertainty_budget,zVar);
        robustObjectiveConstraintLHS.add(qSum);

        model.addConstr(robustObjectiveConstraintLHS,GRB.LESS_EQUAL,0,
                "robust_objective_constraint");

        GRBLinExpr objectiveFunction = new GRBLinExpr();
        objectiveFunction.addTerm(1,totalRisk);
        // Remember that we transformed to a maximization objective
        model.setObjective(objectiveFunction, GRB.MAXIMIZE);


    }

    /**
     * Deterministic objective function using expected value of risk as the
     * point estimate.
     * @param config
     * @param activeLookup
     * @return
     */
    private static GRBLinExpr createExpectedObjectiveFunction(ProblemConfiguration config, Map<Integer, Map<String, Map<Integer, GRBVar>>> activeLookup) {
        GRBLinExpr objectiveFunction = new GRBLinExpr();

        // Find out which interventions are active at each timestep and find
        // the risk of being active at that timestep.
        for (var activeEntry :
                activeLookup.entrySet()) {

            var activeTimeStep = activeEntry.getKey();

            for (var interventionEntry :
                    activeEntry.getValue().entrySet()) {
                var interventionName = interventionEntry.getKey();

                for (var startTimeEntry :
                        interventionEntry.getValue().entrySet()) {
                    var startTime = startTimeEntry.getKey();
                    var intervention = startTimeEntry.getValue();

                    var scenarioMeanRisk =
                            config.getScenarioMeanRisk(interventionName,
                                    startTime,activeTimeStep);

                    objectiveFunction.addTerm(scenarioMeanRisk,intervention);

                }

            }

        }
        return objectiveFunction;
    }

    public static class Solution{
        // Could do basic checks to make sure it's valid and also use a
        // builder call once the user is done.

        // Writeable for supercsv

    }

    public static void main(String[] args) {

        try {



//            var paths = new ArrayList<>(Arrays.asList("C:/Users/iarmstrong" +
//                    "/Downloads" +
//                    "/challenge-roadef" +
//                    "-2020" +
//                    "-master/challenge-roadef-2020-master/A_set/A_10.json",
//                    "C:/Users/iarmstrong" +
//                            "/Downloads" +
//                            "/challenge-roadef" +
//                            "-2020" +
//                            "-master/challenge-roadef-2020-master/A_set/A_13" +
//                            ".json"));

            var paths = new ArrayList<>(Arrays.asList("C:/Users/iarmstrong" +
                            "/Downloads" +
                            "/challenge-roadef" +
                            "-2020" +
                            "-master/challenge-roadef-2020-master/A_set/A_10" +
                            ".json"));

            for (var path :
                    paths) {
                // Read file first before creating stuff or else you end up with
                // empty directories
                byte [] json = Files.readAllBytes(Paths.get(path));
                ProblemConfiguration config =
                        new ObjectMapper().readValue(json, ProblemConfiguration.class);

                // Want to be able to loop over instances and place in
                // appropriate directories for analysis
                var instanceName = FilenameUtils.getBaseName(path);

                System.out.println("Instance name:"+ instanceName);

                String timeStamp =
                        new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date());
                var deterministicPath = String.format("./%s/%s/%s/",timeStamp,
                        instanceName,
                        "deterministic");

                // Orchestrate from the top to the extent possible. Later code
                // should just be saving stuff and running algorithms.
                Files.createDirectories(Paths.get(deterministicPath));

                //Ideally models would just return results without side effects
                // .Not clear how disposal would work though.
                deterministicModel(config, deterministicPath,15*60);

                //TODO Should create run configuration object
                int stepSize;
                int budgetUBound;
                int budgetLBound;
                switch (instanceName) {
                    case "A_11" -> {
                        stepSize = 5;
                        budgetUBound = 0;
                        budgetLBound = 0;
                    }
                    case "A_10" -> {
                        stepSize = 5;
                        budgetUBound = 0;
                        budgetLBound = 0;
                    }
                    case "A_08" -> {
                        stepSize = 1;
                        budgetUBound = 16;
                        budgetLBound = 0;
                    }
                    case "A_09" -> {
                        stepSize = 2;
                        budgetUBound = 100;
                        budgetLBound = 0;
                    }
                    case "A_07" -> {
                        stepSize = 2;
                        budgetUBound = 100;
                        budgetLBound = 0;
                    }
                    case "A_12" -> {
                        stepSize = 2;
                        budgetUBound = 100;
                        budgetLBound = 0;
                    }
                    case "A_13" -> {
                        stepSize = 7;
                        budgetUBound = 300;
                        budgetLBound = 0;
                    }
                    case "A_02" ->{
                        stepSize = 15;
                        budgetUBound = 250;
                        budgetLBound = 1;
                    }
                    case "A_05" -> {
                        stepSize = 2;
                        budgetUBound = 50;
                        budgetLBound = 0;
                    }
                    case "A_14" -> {
                        stepSize = 10;
                        budgetUBound = 500;
                        budgetLBound = 0;
                    }
                    default -> throw new IllegalArgumentException("Not a valid instance " +
                            "name.");
                }

                System.out.println(String.format("Budget Ubound: %d Budget Step " +
                        "Size:%d",budgetUBound,stepSize));
                int currentBudget= budgetLBound;

                while (currentBudget <= budgetUBound) {
                    System.out.println("Budget:" + currentBudget);
                    var robustPath = String.format("./%s/%s/%s/%d/", timeStamp,
                            instanceName,
                            "robust", currentBudget);
                    Files.createDirectories(Paths.get(robustPath));
                    robustBoxModel(new RobustModelConfiguration(config,
                            robustPath, currentBudget), 15*60);
                    currentBudget += stepSize;
                }

            }

        } catch (GRBException e) {
            System.out.println("Error code: " + e.getErrorCode() + ". " +
                    e.getMessage());
        } catch (JsonParseException e) {
            e.printStackTrace();
        } catch (JsonMappingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @JsonIgnoreProperties(value = { "config" })
    private static class RobustModelConfiguration {
        private final ProblemConfiguration config;
        //TODO I don't think this should be in here
        private final String directoryPath;
        private final double uncertaintyBudget;

        private RobustModelConfiguration(ProblemConfiguration config,
                                         String directoryPath,
                                         double uncertaintyBudget) {
            this.config = config;
            this.directoryPath = directoryPath;
            this.uncertaintyBudget = uncertaintyBudget;
        }

        public ProblemConfiguration getConfig() {
            return config;
        }

        public String getDirectoryPath() {
            return directoryPath;
        }

        public double getUncertaintyBudget() {
            return uncertaintyBudget;
        }
    }
}
