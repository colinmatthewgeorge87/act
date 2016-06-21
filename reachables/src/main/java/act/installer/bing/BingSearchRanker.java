package act.installer.bing;

import act.installer.wikipedia.ImportantChemicalsWikipedia;
import act.server.MongoDB;
import com.act.utils.TSVWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBCursor;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BingSearchRanker {

  private static ObjectMapper mapper = new ObjectMapper();

  public static final String OPTION_INPUT_FILEPATH = "i";
  public static final String OPTION_OUTPUT_FILEPATH = "o";
  public static final String OPTION_TSV_INPUT = "t";
  public static final String OPTION_TSV_INPUT_HEADER_NAME = "n";

  public static final String HELP_MESSAGE = StringUtils.join(new String[]{
      "This class adds Bing Search results for a list of molecules in the Installer (actv01) database",
      "and exports the results in a TSV format for easy import in Google spreadsheets."
  }, " ");

  public static final List<Option.Builder> OPTION_BUILDERS = new ArrayList<Option.Builder>() {{
    add(Option.builder(OPTION_INPUT_FILEPATH)
        .argName("INPUT_FILEPATH")
        .desc("The full path to the input file")
        .hasArg().required()
        .longOpt("input_filepath")
        .type(String.class)
    );
    add(Option.builder(OPTION_OUTPUT_FILEPATH)
        .argName("OUTPUT_PATH")
        .desc("The full path where to write the output.")
        .hasArg().required()
        .longOpt("output_path")
        .type(String.class)
    );
    add(Option.builder(OPTION_TSV_INPUT)
        .argName("TSV_INPUT")
        .desc("Whether the input is a TSV file with an InChI column.")
        .longOpt("tsv")
        .type(boolean.class)
    );
    add(Option.builder(OPTION_TSV_INPUT_HEADER_NAME)
        .argName("TSV_INPUT_HEADER_NAME")
        .desc("Header name in case of TSV input.")
        .longOpt("inchi_header_name")
        .type(String.class)
    );
    add(Option.builder("h")
        .argName("help")
        .desc("Prints this help message")
        .longOpt("help")
    );
  }};

  public static final HelpFormatter HELP_FORMATTER = new HelpFormatter();

  static {
    HELP_FORMATTER.setWidth(100);
  }

  public String parseInchiFromBingMetadata(BasicDBObject c) {
    String inchi = (String) c.get("InChI");
    return inchi;
  }

  public Long parseCountFromBingMetadata(BasicDBObject c) {
    Long totalCountSearchResults = (Long) c.get("total_count_search_results");
    return totalCountSearchResults;
  }

  public String parseNameFromBingMetadata(BasicDBObject c) {
    String bestName = (String) c.get("best_name");
    return bestName;
  }

  public static void main(final String[] args) throws IOException {

    Options opts = new Options();
    for (Option.Builder b : OPTION_BUILDERS) {
      opts.addOption(b.build());
    }

    CommandLine cl = null;
    try {
      CommandLineParser parser = new DefaultParser();
      cl = parser.parse(opts, args);
    } catch (ParseException e) {
      System.err.format("Argument parsing failed: %s\n", e.getMessage());
      HELP_FORMATTER.printHelp(ImportantChemicalsWikipedia.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    if (cl.hasOption("help")) {
      HELP_FORMATTER.printHelp(ImportantChemicalsWikipedia.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      return;
    }

    String inputPath = cl.getOptionValue(OPTION_INPUT_FILEPATH);
    String outputPath = cl.getOptionValue(OPTION_OUTPUT_FILEPATH);
    Boolean isTSVInput = cl.hasOption(OPTION_TSV_INPUT);

    BingSearchRanker bingSearchRanker = new BingSearchRanker();

    MoleculeCorpus moleculeCorpus = new MoleculeCorpus();
    if (isTSVInput) {
      String inchiHeaderName = cl.getOptionValue(OPTION_TSV_INPUT_HEADER_NAME);
      moleculeCorpus.buildCorpusFromTSVFile(inputPath, inchiHeaderName);
    } else {
      moleculeCorpus.buildCorpusFromRawInchis(inputPath);
    }

    Set<String> inchis = moleculeCorpus.getUsageTerms();

    MongoDB mongoDB = new MongoDB("localhost", 27017, "actv01");
    BingSearcher bingSearcher = new BingSearcher();
    bingSearcher.addBingSearchResultsForInchiSet(mongoDB, inchis);


    DBCursor cursor = mongoDB.fetchNamesAndBingInformation();

    List<String> headers = new ArrayList<>();
    headers.add("inchi");
    headers.add("best_name");
    headers.add("total_count_search_results");
    headers.add("names_list");

    TSVWriter tsvWriter = new TSVWriter(headers);
    tsvWriter.open(new File(outputPath));


    while (cursor.hasNext()) {
      BasicDBObject o = (BasicDBObject) cursor.next();
      String inchi = bingSearchRanker.parseInchiFromBingMetadata(o);
      if (inchis.contains(inchi)) {
        Map<String, String> row = new HashMap<>();
        System.out.println(bingSearchRanker.parseInchiFromBingMetadata(o));
        row.put("inchi", inchi);
        BasicDBObject xref = (BasicDBObject) o.get("xref");
        BasicDBObject bing = (BasicDBObject) xref.get("BING");
        BasicDBObject metadata = (BasicDBObject) bing.get("metadata");
        row.put("best_name", bingSearchRanker.parseNameFromBingMetadata(metadata));
        row.put("total_count_search_results", bingSearchRanker.parseCountFromBingMetadata(metadata).toString());
        row.put("names_list", mapper.writeValueAsString(mongoDB.getNamesFromBasicDBObject(o, inchi)));
        tsvWriter.append(row);
      }
    }
  }
}
