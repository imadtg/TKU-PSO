import java.io.IOException;

public class main {

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.out.println("Usage: java main <input_file> <output_file>");
            System.out.println("  <input_file>  : Path to the input database file in SPMF format");
            System.out.println("  <output_file> : Path where the discovered patterns will be written");
            System.exit(1);
        }

        String inputFile = args[0];
        String outputFile = args[1];

        TKU_PSO alg = new TKU_PSO(inputFile, outputFile);
        alg.run();
        alg.printStats();
    }
}
