import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Value;

import java.beans.ConstructorProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 0-based time indexing assumed
 * <p>
 * TODO Little time spent checking invariants with the  assumption that most of
 * our time will be spent ingesting cleaned data rather than making our own.
 * No checks to make sure number of time steps matches, that we don't
 * include unnecessary cases that go over the horizon, contiguous values,
 * positive values, etc.
 */
@Builder
@Value
public class ProblemConfiguration {

    //TODO Write tests to make sure your indexing matches the data in the
    // json. We want a canary in case we aren't consistent or change our
    // minds. Also make sure scenarios, mins, and maxes match.

    //TODO move example.json to the resources directory

    // Consider using rootrapper

    // It's not zero indexed! Consider moving arrays and arraylists.

    private Map<String,Map<String,ArrayList<Integer>>> Resources;

    /**
     * Given a season, provide the relevant timesteps. There doesn't seem to
     * be any guarantee ethat the timesteps within a season are contiguous
     * even though that would be likely.
     */
    private Map<String, ArrayList<Integer>> seasons;

    /**
     * Given the name of an intervention, what data is available?
     */
    private Map<String,Intervention> interventions;

    /**
     * TODO This should probably be structured to accomodate the most likely
     * queries. Until we know what those look like, this is OK.
     */
    private Map<String, ArrayList<String>> exclusions;

    private int T;
    private ArrayList<Integer> scenarios_number;
    private double quantile;
    private double alpha;
    private double computationTime;

    @ConstructorProperties({"Resources", "Seasons", "Interventions",
            "Exclusions", "T", "Scenarios_number", "Quantile", "Alpha",
            "ComputationTime"})
    public ProblemConfiguration(Map<String, Map<String, ArrayList<Integer>>> resources,
                                Map<String, ArrayList<Integer>> seasons,
                                Map<String, Intervention> interventions,
                                Map<String, ArrayList<String>> exclusions,
                                int t,
                                ArrayList<Integer> scenarios_number,
                                double quantile,
                                double alpha,
                                double computationTime) {

        for (Map.Entry<String, Intervention> interventionEntry :
                interventions.entrySet()) {
            interventionEntry.setValue(zeroIndexIntervention(interventionEntry.getValue()));
        }

        for (Map.Entry<String, ArrayList<Integer>> season:
                seasons.entrySet()){
            for (int i = 0; i < season.getValue().size() ; i++) {
                int current_value = season.getValue().get(i);
                season.getValue().set(i,current_value-1);

            }
        }

        Resources = resources;
        this.seasons = seasons;
        this.interventions = interventions;
        this.exclusions = exclusions;
        T = t-1;
        this.scenarios_number = scenarios_number;
        this.quantile = quantile;
        this.alpha = alpha;
        this.computationTime = computationTime;



    }

    /**
     * Interventions provided by the official configuration files assume
     * that time is indexed at 1. It's more natural while programming to
     * index these at 0 (ex. arrays based at 0 make more sense than a
     * hashmap based at 1 when you have integer keys).
     *
     * TODO Consider mutating interventions in place to save memory.
     * TODO Consider using deserialization Jackson functionality to create
     * interventions correctly in the first place. That said, the ability to
     * turn the indexing on and off may be a feature in which case there is
     * less of a need for the deserialization approach.
     * @param intervention
     * @return
     */
    private Intervention zeroIndexIntervention(Intervention intervention){

        var updatedWorkload = decreaseWorkloadTimestep(intervention);
        var updatedRisk = decreasedRiskTimestep(intervention);
        var updatedTmax = intervention.tmax-1;

        return new Intervention(updatedTmax,
                intervention.delta,updatedWorkload,updatedRisk);
    }

    private static Map<Integer, Map<Integer, ArrayList<Double>>> decreasedRiskTimestep(Intervention intervention ) {
        Map<Integer, Map<Integer, ArrayList<Double>>> risk = new HashMap<>();

        for (Map.Entry<Integer, Map<Integer, ArrayList<Double>>> startMap:
        intervention.risk.entrySet()){

            int reducedStartTime = startMap.getKey()-1;
            Map<Integer,ArrayList<Double>> updatedStartMap = new HashMap<>();

            for (Map.Entry<Integer, ArrayList<Double>> activeMap:
            startMap.getValue().entrySet()){

                int reducedActiveTimeStep = activeMap.getKey() -1;
                updatedStartMap.put(reducedActiveTimeStep,
                        activeMap.getValue());
            }

            risk.put(reducedStartTime,updatedStartMap);

        }

        return risk;
    }

    /**
     * Given an intervention that is using 1-based indexing for the workload
     * data, convert to 0-based indexing.
     * @param intervention
     * @return
     */
    private static Map<String,Map<Integer,Map<Integer,Double>>> decreaseWorkloadTimestep(Intervention intervention){

        Map<String,Map<Integer,Map<Integer,Double>>> workload = new HashMap<>();

        for (Map.Entry<String, Map<Integer, Map<Integer, Double>>> resourceMap:
                intervention.workload.entrySet()){

            Map<Integer,Map<Integer,Double>> updatedResourceMap =
                    new HashMap<>();

            //Add updated start time maps
            for (Map.Entry<Integer, Map<Integer, Double>> startMap:
                    resourceMap.getValue().entrySet()){

                int reducedStartTimeStep = startMap.getKey()-1;
                Map<Integer,Double> updatedWorkMap = new HashMap<>();

                // Add updated entries
                for (Map.Entry<Integer, Double> activeWorkloadMap:
                        startMap.getValue().entrySet()){

                    int reducedActiveTimeStep = activeWorkloadMap.getKey()-1;
                    updatedWorkMap.put(reducedActiveTimeStep,
                            activeWorkloadMap.getValue());
                }

                updatedResourceMap.put(reducedStartTimeStep,updatedWorkMap);

            }


            workload.put(resourceMap.getKey(),updatedResourceMap);
        }

        return workload;

    }

    public int getNumScenarios(int timestep){
        return scenarios_number.get(timestep);
    }


    public double getWorkload(String intervention, String resource,
                              int startTime, int activeTime){
        return interventions.get(intervention).getWorkload(resource,
                startTime,activeTime);
    }
    public boolean hasResourceWorkloadActiveStep(String intervention,
                                                 String resource, int activeTime){
        return interventions.get(intervention).hasResourceWorkloadActiveStep(resource,activeTime);
    }

    public boolean hasResourceWorkload(String intervention, String resource){

        return interventions.get(intervention).hasResourceWorkload(resource);

    }

    public double getScenarioMeanRisk(String intervention, int startTime,
                                      int activeTime) {
        return interventions.get(intervention).getScenarioMeanRisk(startTime,
                activeTime);
    }
    public double getScenarioStandardDeviationRisk(String intervention,
                                                   int startTime,
                                                   int activeTime) {
        return interventions.get(intervention).getScenarioStandardDeviationRisk(startTime,activeTime);
    }


    public ArrayList<Double> getRiskScenarios(String intervention,
                                              int startTime, int activeTime){
        return interventions.get(intervention).getRiskScenarios(startTime,
                activeTime);
    }

    public double getResourceMax(String resource, int time){
       return Resources.get(resource).get("max").get(time);
    }

    public double getResourceMin(String resource, int time){
        return Resources.get(resource).get("min").get(time);
    }

    @Builder
    @Value
    public static class Intervention{

        /**
         * "tmax" is a pre-computed value corresponding to the latest
         * possible starting time for a given intervention. "'tmax' is the
         * date/period before which the intervention has to be scheduled (if
         * it is scheduled after that date, it will still be ongoing after
         * the planification limit date, which is forbidden)."
         */
        private int tmax;

        /**
         * "'Delta' is the duration of the intervention depending on its
         * starting date."Index indicates the season. Value indicates the
         * time required for the intervention.
         */
        private ArrayList<Integer> delta;

        //TODO Look at jsonpojobuilder

        // TODO Make everything 0 index based and make anything you return
        //  to python 0 index based too

        /**
         * TODO workload seems as though it can be very sparse.
         * Given start time, map to hash of NONZERO values. It's not clear
         * that workload always stops once the active delta completes. Hash
         * because there may be certain segments of the active delta that
         * don't need work from a certain resource.
         */

        // Create a map that indicates which interventions can possibly be
        // active at the current time step. Not clear whether this should exist
        // in this class or be present in the Model type and map to actual
        // variables.

        // You could use an arraylist for start times assuming that you can
        // start at almost any point, which is probably the case
        private Map<String,Map<Integer,Map<Integer, Double>>> workload;

        private Map<Integer,Map<Integer, ArrayList<Double>>> risk;
        
        public int getNumScenarios(){
            var sum = 0;
            for (var activeEntry :
                    risk.entrySet()) {
                for (var startEntry :
                        activeEntry.getValue().entrySet()) {
                    sum+=startEntry.getValue().size();
                }
            }

            return sum;
        }

        @ConstructorProperties({"tmax","Delta","workload","risk"})
        public Intervention(int tmax, ArrayList<Integer> delta, Map<String,
                Map<Integer, Map<Integer, Double>>> workload, Map<Integer,
                Map<Integer, ArrayList<Double>>> risk) {
            this.tmax = tmax;
            this.delta = delta;
            this.workload = workload;
            this.risk = risk;
        }

        /**
         * Handy because not all interventions use all resources and thus
         * wouldn't show up in their dictionary thus causing null. Could
         * return 0, but that typically wastes time and silent errors survive.
         * @param resource
         * @return
         */
        public boolean hasResourceWorkload(String resource){
            return workload.containsKey(resource);
        }

        /**
         * Even when active, you might not have a workload for a resource as
         * seen in A_13, Intervention_531 and in docs: "Contrary to what is
         * shown in the above example, null values are not written in generated
         * instances. Some element might hence be "missing" in the table."
         * @param resource
         * @param activeTime
         * @return
         */
        public boolean hasResourceWorkloadActiveStep(String resource,
                                              int activeTime) {
            return workload.get(resource).containsKey(activeTime);
        }

        /**
         * According to Github readme: Resources hereby mentionned have to
         * be defined in the Resources set. It is possible for a valid
         * startTime not to be present in the map. See Intervention_531 in
         * A_13 for Ressources1 if you assume a start is 1 and can't find a
         * valid mapping to 3 within the active 2 map.
         *
         * "t" : { "st_1": w_1, "st_2": w_2, "st_3": w_3}
         * @param resource
         * @param start_time
         * @param active_time
         * @return
         */
        public double getWorkload(String resource, int start_time,
                                  int active_time) {
            var startMap = workload.get(resource).get(active_time);
            if (startMap.containsKey(start_time)){
                return startMap.get(start_time);
            }
            return 0;
        }

        public ArrayList<Double> getRiskScenarios(int start_time,
                                                  int active_timestep) {
            return risk.get(active_timestep).get(start_time);
        }

        public double getScenarioMeanRisk(int startTime, int activeTime){

            var scenarios = getRiskScenarios(startTime,activeTime);
            double numScenarios = scenarios.size();
            
            var scenarioSum = 0;
            for (double scenario :
                    scenarios) {
                scenarioSum+=scenario;
            }

            return scenarioSum/numScenarios;

        }

        public double getScenarioStandardDeviationRisk(int startTime,
                                                       int activeTime) {

            var scenarioDiffSum=0;
            var scenarioMeanRisk = getScenarioMeanRisk(startTime,activeTime);
            var scenarios = getRiskScenarios(startTime, activeTime);
            for (double scenario: scenarios){

                scenarioDiffSum+= Math.pow(scenario-scenarioMeanRisk,2);

            }

            // Explicit conversion to double for sanity check
            double N= scenarios.size();

            return Math.sqrt(scenarioDiffSum/N);

        }


    }
}
