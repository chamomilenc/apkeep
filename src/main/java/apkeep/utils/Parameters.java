package apkeep.utils;

public class Parameters {

	public static final boolean DEFAULT_MERGE_AP = true;
	public static final int DEFAULT_BDD_TABLE_SIZE = 100000000;
	public static final int DEFAULT_GC_INTERVAL = 100000;
	public static final int DEFAULT_TOTAL_AP_THRESHOLD = 500;
	public static final int DEFAULT_LOW_MERGEABLE_AP_THRESHOLD = 10;
	public static final int DEFAULT_HIGH_MERGEABLE_AP_THRESHOLD = 50;
	public static final double DEFAULT_FAST_UPDATE_THRESHOLD = 0.25;
	public static final int DEFAULT_PRINT_RESULT_INTERVAL = 100000;
	public static final int DEFAULT_WRITE_RESULT_INTERVAL = 1;

	public static boolean MergeAP = DEFAULT_MERGE_AP;

	public static int BDD_TABLE_SIZE = DEFAULT_BDD_TABLE_SIZE;
//	public static int BDD_TABLE_SIZE = 100000000; // works well for airtel
//	public static int BDD_TABLE_SIZE = 10000000; // works well for 4Switch, 27us
//	public static int BDD_TABLE_SIZE = 1000000; // works well for stanford-noacl, 142us
//	public static int BDD_TABLE_SIZE = 1000; // works well for internet2, 22us
	public static int GC_INTERVAL = DEFAULT_GC_INTERVAL;
	public static int TOTAL_AP_THRESHOLD = DEFAULT_TOTAL_AP_THRESHOLD;
	public static int LOW_MERGEABLE_AP_THRESHOLD = DEFAULT_LOW_MERGEABLE_AP_THRESHOLD;
	public static int HIGH_MERGEABLE_AP_THRESHOLD = DEFAULT_HIGH_MERGEABLE_AP_THRESHOLD;
	public static double FAST_UPDATE_THRESHOLD = DEFAULT_FAST_UPDATE_THRESHOLD;

	public static int PRINT_RESULT_INTERVAL = DEFAULT_PRINT_RESULT_INTERVAL;
//	public static int PRINT_RESULT_INTERVAL = 10000;
//	public static int PRINT_RESULT_INTERVAL = 1;
	public static int WRITE_RESULT_INTERVAL = DEFAULT_WRITE_RESULT_INTERVAL;

	/** Reset all mutable global options before constructing a fresh APKeep model. */
	public static void resetDefaults() {
		MergeAP = DEFAULT_MERGE_AP;
		BDD_TABLE_SIZE = DEFAULT_BDD_TABLE_SIZE;
		GC_INTERVAL = DEFAULT_GC_INTERVAL;
		TOTAL_AP_THRESHOLD = DEFAULT_TOTAL_AP_THRESHOLD;
		LOW_MERGEABLE_AP_THRESHOLD = DEFAULT_LOW_MERGEABLE_AP_THRESHOLD;
		HIGH_MERGEABLE_AP_THRESHOLD = DEFAULT_HIGH_MERGEABLE_AP_THRESHOLD;
		FAST_UPDATE_THRESHOLD = DEFAULT_FAST_UPDATE_THRESHOLD;
		PRINT_RESULT_INTERVAL = DEFAULT_PRINT_RESULT_INTERVAL;
		WRITE_RESULT_INTERVAL = DEFAULT_WRITE_RESULT_INTERVAL;
	}
}
