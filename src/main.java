import java.io.IOException;

public class main {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }

        String inputFile = args[0];
        String outputFile = args[1];

        // Parse optional parameters with default values
        int popSize = 20;
        int iterations = 10000;
        int k = 1000;
        boolean avgEstimate = true;

        try {
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "-p":
                    case "--population":
                        popSize = Integer.parseInt(args[++i]);
                        break;
                    case "-i":
                    case "--iterations":
                        iterations = Integer.parseInt(args[++i]);
                        break;
                    case "-k":
                    case "--top-k":
                        k = Integer.parseInt(args[++i]);
                        break;
                    case "-e":
                    case "--estimate":
                        avgEstimate = args[++i].equalsIgnoreCase("avg");
                        break;
                    default:
                        System.out.println("Unknown parameter: " + args[i]);
                        printUsage();
                        System.exit(1);
                }
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            System.out.println("Error parsing parameters: " + e.getMessage());
            printUsage();
            System.exit(1);
        }

        TKU_PSO alg = new TKU_PSO(inputFile, outputFile, popSize, iterations, k, avgEstimate);
        alg.run();
        alg.printStats();
    }

    private static void printUsage() {
        System.out.println("Usage: java main <input_file> <output_file> [options]");
        System.out.println("Required arguments:");
        System.out.println("  <input_file>  : Path to the input database file in SPMF format");
        System.out.println("  <output_file> : Path where the discovered patterns will be written");
        System.out.println("\nOptional arguments:");
        System.out.println("  -p, --population <int>  : Population size (default: 20)");
        System.out.println("  -i, --iterations <int>  : Number of iterations (default: 10000)");
        System.out.println("  -k, --top-k <int>      : Number of top-k HUIs to find (default: 1000)");
        System.out.println("  -e, --estimate <type>  : Estimate type: 'avg' or 'max' (default: avg)");
    }
}
