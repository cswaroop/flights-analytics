package com.aerospike.example.aggregation;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.log4j.Logger;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Language;
import com.aerospike.client.lua.LuaConfig;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.WritePolicy;
import com.aerospike.client.query.Filter;
import com.aerospike.client.query.IndexType;
import com.aerospike.client.query.ResultSet;
import com.aerospike.client.query.Statement;
import com.aerospike.client.task.IndexTask;
import com.aerospike.client.task.RegisterTask;


public class FlightsAnalytics {
	private static final String DISTANCE_BIN = "DISTANCE";
	private static final String AIR_TIME_BIN = "AIR_TIME";
	private static final String ELAPSED_TIME_BIN = "ELAPSED_TIME";
	private static final String ARR_TIME_BIN = "ARR_TIME";
	private static final String DEP_TIME_BIN = "DEP_TIME";
	private static final String DEST_STATE_ABR_BIN = "DEST_STATE_ABR";
	private static final String DEST_CITY_NAME_BIN = "DEST_CITY_NAME";
	private static final String DEST_BIN = "DEST";
	private static final String ORI_STATE_ABR_BIN = "ORI_STATE_ABR";
	private static final String ORI_CITY_NAME_BIN = "ORI_CITY_NAME";
	private static final String ORIGIN_BIN = "ORIGIN";
	private static final String ORI_AIRPORT_ID_BIN = "ORI_AIRPORT_ID";
	private static final String FL_NUM_BIN = "FL_NUM";
	private static final String CARRIER_BIN = "CARRIER";
	private static final String AIRLINE_ID_BIN = "AIRLINE_ID";
	private static final String DAY_OF_MONTH_BIN = "DAY_OF_MONTH";
	private static final String YEAR_BIN = "YEAR";
	private static final String FL_DATE_BIN = "FL_DATE_BIN";
	private static final String FL_DATE_INDEX = "flight_date";
	private static final String FLIGHTS = "flights";
	
	private AerospikeClient client;
	private String seedHost;
	private int port;
	private WritePolicy writePolicy;
	private Policy policy;
	private String namespace;
	private static Logger log = Logger.getLogger(FlightsAnalytics.class);
	private DateFormat format = new SimpleDateFormat("yyyy/MM/dd");
	
	public FlightsAnalytics(String host, int port, String namespace) throws AerospikeException {
		this.client = new AerospikeClient(host, port);
		this.seedHost = host;
		this.port = port;
		this.writePolicy = new WritePolicy();
		this.policy = new Policy();
		this.namespace = namespace;
	}
	public static void main(String[] args) throws java.text.ParseException {
		try {
			Options options = new Options();
			options.addOption("h", "host", true, "Server hostname (default: localhost)");
			options.addOption("p", "port", true, "Server port (default: 3000)");
			options.addOption("n", "namespace", true, "Namespace (default: test)");
			options.addOption("f", "file", true, "Data file (default: data)");
			options.addOption("u", "usage", false, "Print usage.");

			CommandLineParser parser = new PosixParser();
			CommandLine cl = parser.parse(options, args, false);


			String host = cl.getOptionValue("h", "127.0.0.1");
			String portString = cl.getOptionValue("p", "3000");
			String namespace = cl.getOptionValue("n", "test");
			String fileName = cl.getOptionValue("f", "data");
			int port = Integer.parseInt(portString);
			log.debug("Host: " + host);
			log.debug("Port: " + port);
			log.debug("Namespace: " + namespace);
			if (cl.hasOption("f"))
				log.debug("Data file name: " + fileName);

			@SuppressWarnings("unchecked")
			List<String> cmds = cl.getArgList();
			if (cmds.size() == 0 && cl.hasOption("u")) {
				logUsage(options);
				return;
			}

			FlightsAnalytics sa = new FlightsAnalytics(host, port, namespace);
			if (cl.hasOption("f"))
				sa.loadData(fileName);
			else
				sa.aggregation();


		} catch (ParseException e) {
			log.error("Critical error", e);
		} catch (AerospikeException e) {
			log.error("Aerospike error", e);
		}
	}
	public void aggregation() throws AerospikeException, java.text.ParseException {

		/*
		 * Register UDF
		 */
		LuaConfig.SourceDirectory = "udf"; // Setting the Lua cache directory
		File udfFile = new File("udf/simple_aggregation.lua");
		RegisterTask task = this.client.register(this.policy, 
				udfFile.getPath(), 
				udfFile.getName(), 
				Language.LUA); 
		task.waitTillComplete();
		log.info("registered UDF");

		/*
		 * create time stamps for query from
		 * the start date and end date
		 */
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
		Date startDate = sdf.parse("2012-01-15");
		long startTimeStamp = startDate.getTime() / 1000;
		Date endDate = sdf.parse("2012-01-15");
		long endTimeStamp = endDate.getTime() / 1000;
		/*
		 * build the query
		 */
		Statement stmt = new Statement();
		stmt.setNamespace(this.namespace);
		stmt.setSetName(FLIGHTS);
		stmt.setFilters(Filter.range(FL_DATE_BIN, startTimeStamp, endTimeStamp));
		log.info("built query");

		// Execute the query
		ResultSet rs = client.queryAggregate(null, stmt, "simple_aggregation", "late_flights_by_airline");
		log.info("executed aggregation");
		if (rs != null){
			Map map = null;
			log.info("Airlines with late flights:");
			long  start = System.nanoTime();
			while (rs.next()){
				map = (Map) rs.getObject();
			}
			long stop = System.nanoTime();
			printMap(map);
			log.info("Time: " + (stop-start)/1000000);
		} else {
			log.info("Nothing returned");
		}
	}

	private void printMap(Map map) {
		if (map == null)
			return;
		Set keySet = map.keySet();
		for (Object key : keySet){
			Map<String, Long> airline = (Map<String, Long>) map.get(key);
			log.info(String.format("%s: %6d %6d %3d%%", key.toString(), airline.get("flights"), airline.get("late"), airline.get("percent")));
		}

	}
	public void loadData(String fileName) {
		try {
			/*
			 * create index on itemNo
			 */
			IndexTask indexTask = this.client.createIndex(this.policy, this.namespace, FLIGHTS, 
					FL_DATE_INDEX, FL_DATE_BIN, IndexType.NUMERIC);
			indexTask.waitTillComplete();
			log.info("created index");

			File file = new File(fileName);
			if (file.exists()) {
				if (file.isDirectory()){
					File[] csvFiles =file.listFiles();
					for (final File aFile : csvFiles){
						if (aFile.getName().startsWith("."))
							continue;
						loadFile(aFile);
					}
				} else {
					loadFile(file);
				}
			}
		} catch (Exception e){
			log.error("Failed to upload data from " + fileName, e);
		}
	}
	public void loadFile(File file) throws NumberFormatException, IOException, AerospikeException, java.text.ParseException{
		if (file.exists()) {

			String line =  "";
			BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
			while ((line = br.readLine()) != null) {

				// use comma as separator
				String[] flight = line.split(",");

				/*
				 * write the record to Aerospike
				 * NOTE: Bin names must not exceed 14 characters
				 */
				client.put(this.writePolicy,
						new Key(this.namespace, FLIGHTS,flight[0].trim() ),
						new Bin(YEAR_BIN, Integer.parseInt(flight[1].trim())),	
						new Bin(DAY_OF_MONTH_BIN, Integer.parseInt(flight[2].trim())),
						new Bin(FL_DATE_BIN, toTimeStamp(flight[3].trim())),
						new Bin(AIRLINE_ID_BIN, Integer.parseInt(flight[4].trim())),	
						new Bin(CARRIER_BIN, flight[5].trim()),
						new Bin(FL_NUM_BIN, Integer.parseInt(flight[6].trim())),
						new Bin(ORI_AIRPORT_ID_BIN, Integer.parseInt(flight[7].trim())),
						new Bin(ORIGIN_BIN, flight[8].trim()),
						new Bin(ORI_CITY_NAME_BIN, flight[9].trim()),
						new Bin(ORI_STATE_ABR_BIN, flight[10].trim()),
						new Bin(DEST_BIN, flight[11].trim()),
						new Bin(DEST_CITY_NAME_BIN, flight[12].trim()),
						new Bin(DEST_STATE_ABR_BIN, flight[13].trim()),
						new Bin(DEP_TIME_BIN, Integer.parseInt(flight[14].trim())),
						new Bin(ARR_TIME_BIN, Integer.parseInt(flight[15].trim())),
						new Bin(ELAPSED_TIME_BIN, Integer.parseInt(flight[16].trim())),
						new Bin(AIR_TIME_BIN, Integer.parseInt(flight[17].trim())),
						new Bin(DISTANCE_BIN, Integer.parseInt(flight[18].trim()))
						);

				log.debug("Flight [ID= " + flight[0] 
						+ " , FL_DATE=" + flight[3] 
								+ " , CARRIER=" + flight[5] 
										+ " , FL_NUM=" + flight[6] 
												+ "]");

			}
			br.close();
			log.info("Successfully uploaded " + file.getName());
		} else {
			log.error("Failed to upload " + file.getName() + " because is does not exist.");
		}

	}

	private long toTimeStamp(String dateString) throws java.text.ParseException{
		// format 2012/11/11
			Date formatDate = format.parse(dateString);
			long miliSecondForDate = formatDate.getTime();
			return miliSecondForDate / 1000;
	}

	private static void logUsage(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		String syntax = FlightsAnalytics.class.getName() + " [<options>]";
		formatter.printHelp(pw, 100, syntax, "options:", options, 0, 2, null);
		log.info(sw.toString());
	}
}
