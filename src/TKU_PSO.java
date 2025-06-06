import java.io.*;
import java.util.*;


//@author Simen Carstensen
public class TKU_PSO {
    //DO NOT CHANGE!
    List<Pair[]> database = new ArrayList<>(); //the database after pruning
    private Particle gBest; //the global fittest particle (or a top-K HUI selected with RWS)
    private Particle[] pBest; //list of personal fittest offspring of each particle
    private Particle[] population; //the population
    private int maxTransactionLength = 0; //the number of items in the largest transaction
    private ArrayList<Item> HTWUI = new ArrayList<>(); //list of all HTWUI
    private HashSet<BitSet> explored; //set of current explored particles/itemsets
    private HashMap<Integer, Integer> itemNamesRev = new HashMap<>(); //maps new item names to original
    private int std; //mean deviation between maxUtils and avgUtils
    private int lowEst = 0; //number of fitness underestimates
    private int highEst = 0; //number of fitness overestimates
    private int minSolutionFitness = 0; //the smallest utility of current top-k HUIs (0 if less than k current HUIs)
    private Solutions solutions; //class that handles storage of the top-k HUIs
    private boolean newS = false; //true if a new top-k HUI is discovered at current iteration
    private TreeSet<Item> sizeOneItemsets; //set with all 1-itemsets, sorted according to utility
    private boolean runRWS = true; //true if RWS on gBest should be used at the current iteration
    private long utilSum = 0; // the combined utility of all current top-k HUIs (for RWS)
    private long twuSum = 0; //the combined twu of all HTWUIs (for RWS)


    //file paths
    private final String input;  // input file path
    private final String output; // output file path

    //Algorithm parameters
    private final int pop_size; // the size of the population
    private final int iterations; // the number of iterations before termination
    private final int k; //the desired number of top-k HUIs
    private final boolean avgEstimate; //true: use average estimates, false: use maximum estimates

    // Default values for algorithm parameters
    private static final int DEFAULT_POP_SIZE = 20;
    private static final int DEFAULT_ITERATIONS = 10000;
    private static final int DEFAULT_K = 1000;
    private static final boolean DEFAULT_AVG_ESTIMATE = true;

    //stats
    double maxMemory; // the maximum memory usage
    long startTimestamp; // the time the algorithm started
    long endTimestamp; // the time the algorithm terminated


    // this class represent an item and its utility in a transaction
    private static class Pair implements Comparable<Pair> {
        final int item;
        final int utility;

        public Pair(int item, int utility) {
            this.item = item;
            this.utility = utility;
        }

        public int compareTo(Pair o) {
            return (this.item <= o.item) ? -1 : 1;
        }

        public int getUtility() {
            return utility;
        }
    }

    //used to store twu and util of each item during init() (instead of using two maps)
    private static class TwuAndUtil {
        final int twu;
        final int utility;

        public TwuAndUtil(int twu, int util) {
            this.twu = twu;
            this.utility = util;
        }
    }

    //stores various item info
    private static class Item implements Comparable<Item> {
        final int item; //item name
        BitSet TIDS; //TidSet of item
        int twu; // TWU of item
        int totalUtil = 0; //utility of item
        int avgUtil; // average utility of item
        int maxUtil = 0; // maximum utility of item

        public Item(int item) {
            TIDS = new BitSet();
            this.item = item;
        }

        public String toString() {
            return String.valueOf(item);
        }

        public int compareTo(Item o) {
            return (this.totalUtil <= o.totalUtil) ? -1 : 1;
        }
    }

    // this class represent a particle (a generated solution)
    private static class Particle implements Comparable<Particle> {
        BitSet X; // itemset of particle (encoding vector)
        int fitness; // fitness/utility of particle
        int estFitness; // estimated fitness of particle

        public Particle(int size) {
            this.X = new BitSet(size);
        }

        public Particle(BitSet bitset, int fitness) {
            this.X = (BitSet) bitset.clone();
            this.fitness = fitness;
        }

        public int compareTo(Particle o) {
            return (this.fitness <= o.fitness) ? -1 : 1;
        }
    }

    //class for maintaining the top-k solutions
    private class Solutions {
        final int capacity; //max size of set, i.e. -> k
        TreeSet<Particle> sol = new TreeSet<>(Comparator.reverseOrder()); //reversed for faster Roulette wheel sel.
        //TreeSet cannot contain duplicate elements. The compareTo of Particle must therefore not return 0 for any case
        //as it will not be able to store solutions with identical fitness

        public Solutions(int k) {
            this.capacity = k;
        }

        //adds a new top-k HUI to the solution set
        public void add(Particle p) {
            if (sol.size() == capacity) {
                utilSum -= sol.pollLast().fitness; //set is full, remove the kth HUI and update utilSum
            }
            //disable RWS on gBest this iteration if particle is the new fittest solution
            if (!sol.isEmpty()) {
                runRWS = (p.fitness > sol.first().fitness) ? false : runRWS;
            }
            sol.add(p); // add the new HUI
            utilSum += p.fitness; //update utilSum
            newS = true; //notify new solution is discovered
            if (sol.size() == capacity) {
                minSolutionFitness = sol.last().fitness; //update MSF
            }
        }

        public TreeSet<Particle> getSol() {
            return sol;
        }

        public int getSize() {
            return sol.size();
        }

    }

    /**
     * Constructor that takes input and output file paths with default algorithm parameters
     * @param inputFile Path to the input database file in SPMF format
     * @param outputFile Path where the discovered patterns will be written
     */
    public TKU_PSO(String inputFile, String outputFile) {
        this(inputFile, outputFile, DEFAULT_POP_SIZE, DEFAULT_ITERATIONS, DEFAULT_K, DEFAULT_AVG_ESTIMATE);
    }

    /**
     * Constructor that takes input and output file paths and algorithm parameters
     * @param inputFile Path to the input database file in SPMF format
     * @param outputFile Path where the discovered patterns will be written
     * @param popSize Population size for PSO
     * @param iterations Number of iterations before termination
     * @param k Number of desired top-k HUIs
     * @param avgEstimate Whether to use average estimates (true) or maximum estimates (false)
     */
    public TKU_PSO(String inputFile, String outputFile, int popSize, int iterations, int k, boolean avgEstimate) {
        this.input = inputFile;
        this.output = outputFile;
        this.pop_size = popSize;
        this.iterations = iterations;
        this.k = k;
        this.avgEstimate = avgEstimate;
    }

    /**
     * Call this method to run the algorithm. File paths and algorithm parameters must be set in top of class
     *
     * @throws IOException
     */
    public void run() throws IOException {
        maxMemory = 0;
        startTimestamp = System.currentTimeMillis();

        init(); //initialize db from input file and prune
        solutions = new Solutions(k); //class for maintaining the top-k HUIs
        checkMemory();

        System.out.println("HTWUI_SIZE: " + HTWUI.size());

        sizeOneItemsets = new TreeSet<>();
        std = 0; // the deviation
        for (Item item : HTWUI) {
            item.avgUtil = 1 + (item.totalUtil / item.TIDS.cardinality()); //find average utility
            std += item.maxUtil - item.avgUtil; //update deviation
            sizeOneItemsets.add(item); //store 1-itemset (for population initialization strategy)
            twuSum += item.twu; //update twu sum
        }
        explored = new HashSet<>(); //set for explored particles
        explored.add(new BitSet(HTWUI.size())); //avoids edge-case for empty particle

        if (HTWUI.size() != 0) {
            std = std / HTWUI.size(); // mean deviation
            generatePop(); //initialize the population
            fillSolutions(); // fill the solution-set with the remaining 1-itemsets
            List<Double> probRange = rouletteTopK(); //roulette probabilities for current top-k HUIs

            for (int i = 0; i < iterations; i++) { //<-----------------MAIN LOOP
                runRWS = true;
                update(); //update and evaluate each particle in population
                if (i > 1 && runRWS) { //RWS update of gBest
                    if (newS) { //new solutions are discovered, probability range must be updated
                        probRange = rouletteTopK();
                        newS = false;
                    }
                    int pos = rouletteSelect(probRange);
                    selectGBest(pos);
                }
                //Tighten std if mostly overestimates are made (only relevant when avgEstimate is active)
                if (i % 25 == 0 && highEst > 0 && i > 0 && std != 1) {
                    std = ((double) lowEst / highEst < 0.01) ? std / 2 : std;
                }
            }
        }
        endTimestamp = System.currentTimeMillis();
        checkMemory();
        writeOut();
    }


    /**
     * Initializes Pop_size number of particles for the population.
     * Each particle is initialized to a 1-itemset, starting from the fittest one.
     * If #1-itemsets < Pop_size, then the leftover particles are initialized with RWS based on TWU
     */
    private void generatePop() {
        List<Double> rouletteProbabilities = (HTWUI.size() < pop_size) ? rouletteTWU() : null;
        population = new Particle[pop_size];
        pBest = new Particle[pop_size];
        for (int i = 0; i < pop_size; i++) {
            Particle p = new Particle(HTWUI.size());
            if (!sizeOneItemsets.isEmpty()) { //initialize particle to next 1-itemset
                p.X.set(sizeOneItemsets.pollLast().item);
            } else { //RWS initialization
                //k is the number of items to include in the particle
                int k = (int) (Math.random() * maxTransactionLength) + 1;
                //j is the current number of items that has been included
                int j = 0;
                while (j < k) {
                    int pos = rouletteSelect(rouletteProbabilities); //select item
                    //if item is not previously selected, select it and increment j.
                    if (!p.X.get(HTWUI.get(pos).item)) {
                        p.X.set(HTWUI.get(pos).item);
                        j++;
                    }
                }
            }
            BitSet tidSet = pev_check(p);
            p.fitness = calcFitness(p, tidSet, -1);
            population[i] = p;
            pBest[i] = new Particle(p.X, p.fitness); //initialize pBest
            if (!explored.contains(p.X)) {
                if (p.fitness > minSolutionFitness) { //check if current top-k HUI
                    solutions.add(new Particle(p.X, p.fitness));
                }
            }
            if (i == 0) {
                gBest = new Particle(p.X, p.fitness); //initialize gBest
            } else {
                if (p.fitness > gBest.fitness) {
                    gBest = new Particle(p.X, p.fitness); //update gBest
                }
            }
            explored.add((BitSet) p.X.clone()); //set particle as explored
        }
    }

    /**
     * Fills the solution-set with 1-itemsets.
     * Repeats until there are k solutions or there are no more 1-itemsets
     * Purpose: increase minimum solution fitness
     */
    private void fillSolutions() {
        while (solutions.getSize() < k && !sizeOneItemsets.isEmpty()) {
            Item item = sizeOneItemsets.pollLast();
            Particle p = new Particle(HTWUI.size());
            p.X.set(item.item);
            p.fitness = item.totalUtil;
            solutions.add(p);
            explored.add(p.X);
        }
        sizeOneItemsets = null;
    }


    /**
     * The pev-check verifies that the particle exists in the database and modifies it if not.
     * it also calculates the avg/max fitness estimate and returns the TidSet of the particle.
     *
     * @param p The particle
     * @return tidSet: The transactions the particle occur
     */
    private BitSet pev_check(Particle p) {
        int item = p.X.nextSetBit(0);
        p.estFitness = avgEstimate ? HTWUI.get(item - 1).avgUtil : HTWUI.get(item - 1).maxUtil;
        if (p.X.cardinality() == 1) {
            return HTWUI.get(item - 1).TIDS; //avoids bitset clone for 1-itemsets
        }
        BitSet tidSet = (BitSet) HTWUI.get(item - 1).TIDS.clone(); //initial tidSet
        for (int i = p.X.nextSetBit(item + 1); i != -1; i = p.X.nextSetBit(i + 1)) {
            if (tidSet.intersects(HTWUI.get(i - 1).TIDS)) { //the item has common transactions with current tidSet
                tidSet.and(HTWUI.get(i - 1).TIDS); //update tidSet
                //append avg- or max util of the item to the fitness estimate
                p.estFitness += avgEstimate ? (HTWUI.get(i - 1).avgUtil) : (HTWUI.get(i - 1).maxUtil);
            } else {
                p.X.clear(i); // no common transactions, remove the item from the particle
            }
        }
        return tidSet; //the tidSet of the pev-checked particle
    }


    /**
     * Calculates the fitness of a particle
     *
     * @param p      The particle
     * @param tidSet TidSet of the particle
     * @param idx    The position of the particle in the population (to reference pBest), set to -1 if first population
     * @return The fitness of the particle
     */
    private int calcFitness(Particle p, BitSet tidSet, int idx) {
        //The particle only contains 1 item, return the fitness calculated during pre-processing
        if (p.X.cardinality() == 1) {
            return HTWUI.get(p.X.nextSetBit(0) - 1).totalUtil;
        }

        //estimate the fitness
        int support = tidSet.cardinality();
        int est = p.estFitness * support;
        int buffer = avgEstimate ? (std * support) : 0;
        if (idx != -1) {
            if (est + buffer < minSolutionFitness && est < pBest[idx].fitness) {
                return 0;// Skip fitness calculation
            }
        }

        //calculate exact fitness
        int fitness = 0;
        for (int i = tidSet.nextSetBit(0); i != -1; i = tidSet.nextSetBit(i + 1)) {
            int q = 0; //current index in transaction
            int item = p.X.nextSetBit(0); //current item we are looking for
            while (item != -1) {
                if (database.get(i)[q].item == item) { //found item in transaction
                    fitness += database.get(i)[q].utility; //append utility to fitness
                    item = p.X.nextSetBit(item + 1); //select next item in the itemset
                }
                q++;
            }
        }

        //Update overestimates and underestimates
        if (est + buffer < fitness) {
            lowEst++;
        } else {
            highEst++;
        }
        return fitness;
    }


    /**
     * Updates population and checks for new top-k HUIs
     */
    private void update() {
        for (int i = 0; i < pop_size; i++) {
            Particle p = population[i];
            List<Integer> diffList = bitDiff(pBest[i], p); //different items between pBest and current particle
            changeParticle(diffList, p); //change a random number of these items in p
            diffList = bitDiff(gBest, p); //repeat for gBest
            changeParticle(diffList, p);

            if (explored.contains(p.X)) { //the particle is already explored, change one random item
                int rand = (int) (HTWUI.size() * Math.random());
                Item item = HTWUI.get(rand); //the selected item
                if (item.twu < minSolutionFitness) {
                    p.X.clear(item.item); // item unpromising, always clear
                } else {
                    p.X.flip(item.item);
                }
            }
            //avoid PEV-check and fit. calc. if particle is already explored
            if (!explored.contains(p.X)) {
                BitSet copy = (BitSet) p.X.clone(); //particle before pev
                BitSet tidSet = pev_check(p);
                //check if explored again because pev_check can change the particle
                if (!explored.contains(p.X)) {
                    p.fitness = calcFitness(p, tidSet, i);
                    //update pBest and gBest
                    if (p.fitness > pBest[i].fitness) {
                        Particle pCopy = new Particle(p.X, p.fitness);
                        pBest[i] = pCopy;
                        if (p.fitness > gBest.fitness) {
                            gBest = pCopy;
                        }
                    }
                    // check if current top-k HUI
                    if (p.fitness > minSolutionFitness) {
                        solutions.add(new Particle(p.X, p.fitness));
                    }
                    explored.add((BitSet) p.X.clone()); //set current particle as explored
                }
                explored.add(copy); //set particle before PEV-check as explored
            }
        }
    }

    /**
     * Flips a random number of bits in current particle, only bits that are in diffList are considered
     *
     * @param diffList bit differences between particle and pBest/gBest
     * @param p        Particle to change
     */
    private void changeParticle(List<Integer> diffList, Particle p) {
        if (diffList.size() > 0) {
            int num = (int) (diffList.size() * Math.random() + 1); //number of items to change
            for (int i = 0; i < num; i++) {
                int pos = (int) (diffList.size() * Math.random());
                //select the item and remove it from diffList
                Item item = HTWUI.get(diffList.remove(pos) - 1);
                if (item.twu < minSolutionFitness) {
                    p.X.clear(item.item); //item unpromising, always clear
                } else {
                    p.X.flip(item.item); //flip the bit of the selected item
                }
            }
        }
    }

    /**
     * Computes the bit difference between a particle and pBest/gBest
     *
     * @param best pBest/gBest
     * @param p    the particle
     * @return List containing the different bit positions(items)
     */
    private List<Integer> bitDiff(Particle best, Particle p) {
        List<Integer> diffList = new ArrayList<>();
        BitSet temp = (BitSet) best.X.clone();
        temp.xor(p.X);
        for (int i = temp.nextSetBit(0); i != -1; i = temp.nextSetBit(i + 1)) {
            diffList.add(i);
        }
        return diffList;
    }

    /**
     * Creates probability range for roulette wheel selection on items, based on their TWU
     *
     * @return List of item probability ranges
     */
    private List<Double> rouletteTWU() {
        List<Double> probRange = new ArrayList<>();
        double sum = 0;
        //Set probabilities based on TWU-proportion
        for (Item item : HTWUI) {
            sum += item.twu;
            double percent = sum / twuSum;
            probRange.add(percent);
        }
        return probRange;
    }

    /**
     * Roulette wheel selection. Selects a winner based on given probability range and a generated random number
     *
     * @param probRange list of probability ranges
     * @return Index of winner in list
     */
    private int rouletteSelect(List<Double> probRange) { //TODO: make binary search
        double rand = Math.random();
        if (rand <= probRange.get(0)) {
            return 0;
        }
        int pos = 0;
        for (int i = 1; i < probRange.size(); i++) {
            if (rand > probRange.get(i - 1) && rand <= probRange.get(i)) {
                pos = i;
                break;
            }
        }
        return pos;
    }


    /**
     * updates gBest to the top-k HUI at pos
     *
     * @param pos the position of the selected HUI in the solution set
     */
    private void selectGBest(int pos) { //TODO: find more efficient way
        int c = 0;
        for (Particle p : solutions.getSol()) {
            if (c == pos) {
                gBest = p;
                break;
            }
            c++;
        }
    }


    /**
     * creates probability range for roulette wheel selection on current top-k HUIs, based on their fitness
     *
     * @return list of top-k HUIs probability ranges
     */
    private List<Double> rouletteTopK() {
        List<Double> probRange = new ArrayList<>();
        double sum = 0;
        for (Particle hui : solutions.getSol()) {
            sum += hui.fitness;
            double percent = sum / utilSum;
            probRange.add(percent);
        }
        return probRange;
    }


    /**
     * Reads the input file, prunes unpromising items and initializes the database matrix.
     */
    private void init() throws IOException {
        String currentLine;
        Map<Integer, TwuAndUtil> twuAndUtilMap = new HashMap<>();
        //1st DB-Scan: calculate TWU and utility of each item
        try (BufferedReader reader = new BufferedReader(new FileReader(input))) {
            while ((currentLine = reader.readLine()) != null) {
                String[] split = currentLine.split(":");
                String[] items = split[0].split(" ");
                String[] utilities = split[2].split(" ");
                int transactionUtility = Integer.parseInt(split[1]);
                for (int i = 0; i < items.length; i++) {
                    int item = Integer.parseInt(items[i]);
                    int util = Integer.parseInt(utilities[i]);
                    //update item twu
                    TwuAndUtil tau = twuAndUtilMap.get(item);
                    int twu = (tau == null) ?
                            transactionUtility : tau.twu + transactionUtility;
                    //update item utility
                    int currUtil = (tau == null) ?
                            util : tau.utility + util;
                    twuAndUtilMap.put(item, new TwuAndUtil(twu, currUtil));
                }
            }
        }

        //Set minUtil to utility of kth fittest 1-itemset
        ArrayList<Pair> utils = new ArrayList<>(twuAndUtilMap.size());
        for (Map.Entry<Integer, TwuAndUtil> e : twuAndUtilMap.entrySet()) {
            utils.add(new Pair(e.getKey(), e.getValue().utility));
        }
        utils.sort(Comparator.comparingInt(Pair::getUtility).reversed()); //sort based on utility
        int minUtil = (k <= utils.size()) ? utils.get(k - 1).utility : 0; //set min utility
        System.out.println("minUtil: " + minUtil);

        //rename items from 1 to #1-HTWUI, items with high utility has name closer to 1
        //--> reduces memory usage (bec. bitset)
        //--> faster fit. calc. (bec. promising items are early in trans. -> Many particles will contain these)
        //--> better PEV-check (bec. promising items are evaluated first)
        HashMap<Integer, Integer> itemNames = new HashMap<>();
        int name = 1;
        for (Pair p : utils) {
            TwuAndUtil tau = twuAndUtilMap.get(p.item);
            if (tau.twu >= minUtil) { //check if the item is HTWUI
                itemNames.put(p.item, name);
                itemNamesRev.put(name, p.item);
                //initialize some needed info for the item
                Item item = new Item(name);
                item.twu = tau.twu;
                item.totalUtil = tau.utility;
                HTWUI.add(item);
                name++;
            }
        }

        //2nd DB-scan: prune and initialize db
        try (BufferedReader reader = new BufferedReader(new FileReader(input))) {
            int tid = 0;
            while ((currentLine = reader.readLine()) != null) {
                String[] split = currentLine.split(":");
                String[] items = split[0].split(" ");
                String[] utilities = split[2].split(" ");
                List<Pair> transaction = new ArrayList<>();
                for (int i = 0; i < items.length; i++) {
                    int item = Integer.parseInt(items[i]);
                    int util = Integer.parseInt(utilities[i]);
                    if (itemNames.containsKey(item)) { //the item is HTWUI
                        item = itemNames.get(item); //get the new name
                        transaction.add(new Pair(item, util)); //store in transaction with new name
                        Item itemObj = HTWUI.get(item - 1);
                        itemObj.TIDS.set(tid); //update the item's TidSet
                        itemObj.maxUtil = Math.max(itemObj.maxUtil, util); //update the item's maximum utility
                    }
                }
                if (!transaction.isEmpty()) {
                    Collections.sort(transaction); //sort transaction according to item name (much faster fitness calc)
                    //update longest transaction (for roulette wheel initialization)
                    maxTransactionLength = Math.max(maxTransactionLength, transaction.size());
                    //convert transaction to array (better performance in fitness calc)
                    Pair[] trans = new Pair[transaction.size()];
                    transaction.toArray(trans);
                    database.add(trans); //store revised transaction in db
                    tid++; //increment transaction id
                }
            }
        }
    }


    private void writeOut() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Particle p : solutions.getSol()) {
            for (int i = p.X.nextSetBit(0); i != -1; i = p.X.nextSetBit(i + 1)) {
                sb.append(itemNamesRev.get(i));
                sb.append(" ");
            }
            sb.append("#UTIL: ");
            sb.append(p.fitness);
            sb.append(System.lineSeparator());
        }
        BufferedWriter w = new BufferedWriter(new FileWriter(output));
        w.write(sb.toString());
        w.close();
    }

    /**
     * Print statistics about the latest execution to System.out.
     */
    public void printStats() {
        System.out
                .println("============= STATS ==============");
        System.out.println(" Total time ~ " + (endTimestamp - startTimestamp)
                + " ms");
        System.out.println(" Memory ~ " + maxMemory + " MB");
        System.out.println(" Discovered Utility   : " + utilSum);
        System.out.println(" Min Solution Fitness : " + minSolutionFitness);
        System.out
                .println("==================================");
    }

    private void checkMemory() {
        double currentMemory = (Runtime.getRuntime().totalMemory() - Runtime
                .getRuntime().freeMemory()) / 1024d / 1024d;
        maxMemory = Math.max(maxMemory, currentMemory);
    }
}