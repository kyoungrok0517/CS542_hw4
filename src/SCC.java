import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.logging.Logger;

import edu.cmu.graphchi.ChiFilenames;
import edu.cmu.graphchi.ChiLogger;
import edu.cmu.graphchi.ChiVertex;
import edu.cmu.graphchi.GraphChiContext;
import edu.cmu.graphchi.GraphChiProgram;
import edu.cmu.graphchi.datablocks.IntConverter;
import edu.cmu.graphchi.engine.GraphChiEngine;
import edu.cmu.graphchi.engine.VertexInterval;
import edu.cmu.graphchi.preprocessing.EdgeProcessor;
import edu.cmu.graphchi.preprocessing.FastSharder;
import edu.cmu.graphchi.preprocessing.VertexProcessor;

/**
 * Example application for computing the weakly connected components of a graph.
 * The algorithm uses label exchange: each vertex first chooses a label equaling
 * its id; on the subsequent iterations each vertex sets its label to be the
 * minimum of the neighbors' labels and its current label. Algorithm finishes
 * when no labels change. Each vertex with same label belongs to same component.
 * 
 * @author akyrola
 */
public class SCC implements GraphChiProgram<Integer, Integer> {

	private static Logger logger = ChiLogger.getLogger("SCC");

	//
	private static int superstep = 0;
	private static int CONTRACTED_GRAPH_OUTPUT;

	public void update(ChiVertex<Integer, Integer> vertex,
			GraphChiContext context) {
		final int iteration = context.getIteration();
		final int numEdges = vertex.numEdges();

		vertex.setValue(iteration);

		// System.out.println(vertex.getId() + "\t" + vertex.numInEdges() + "\t"
		// + vertex.numOutEdges());
	}

	public void beginIteration(GraphChiContext ctx) {
		final int iteration = ctx.getIteration();

		System.out.println(iteration);
	}

	public void endIteration(GraphChiContext ctx) {
	}

	public void beginInterval(GraphChiContext ctx, VertexInterval interval) {
	}

	public void endInterval(GraphChiContext ctx, VertexInterval interval) {
	}

	public void beginSubInterval(GraphChiContext ctx, VertexInterval interval) {
	}

	public void endSubInterval(GraphChiContext ctx, VertexInterval interval) {
	}

	/**
	 * Initialize the sharder-program.
	 * 
	 * @param graphName
	 * @param numShards
	 * @return
	 * @throws java.io.IOException
	 */
	protected static FastSharder createSharder(String graphName, int numShards)
			throws IOException {
		return new FastSharder<Integer, Integer>(graphName, numShards,
				new VertexProcessor<Integer>() {
					public Integer receiveVertexValue(int vertexId, String token) {
						return 0;
					}
				}, new EdgeProcessor<Integer>() {
					public Integer receiveEdge(int from, int to, String token) {
						return 0;
					}
				}, new IntConverter(), new IntConverter());
	}

	private static class BiDirLabel {
		private Integer smallerOne;
		private Integer largerOne;

		public Integer neighborLabel(int myid, int nbid) {
			if (myid < nbid) {
				return largerOne;
			} else {
				return smallerOne;
			}
		}

		public Integer myLabel(int myid, int nbid) {
			if (myid < nbid) {
				return smallerOne;
			} else {
				return largerOne;
			}
		}

		public boolean deleted() {
			return (smallerOne == null);
		}
	}

	private static class SCCInfo {
		private int color;
		private boolean confirmed;

		public SCCInfo() {
			this.color = 0;
			this.confirmed = false;
		}

		public SCCInfo(int color) {
			this.color = color;
			this.confirmed = false;
		}

		public SCCInfo(int color, boolean confirmed) {
			this.color = color;
			this.confirmed = confirmed;
		}
	}

	/**
	 * Usage: java edu.cmu.graphchi.demo.ConnectedComponents graph-name
	 * num-shards filetype(edgelist|adjlist) For specifying the number of
	 * shards, 20-50 million edges/shard is often a good configuration.
	 */
	public static void main(String[] args) throws Exception {
		String baseFilename = args[0];
		int nShards = Integer.parseInt(args[1]);
		String fileType = (args.length >= 3 ? args[2] : null);

		/* Create shards */
		FastSharder sharder = createSharder(baseFilename, nShards);
		if (baseFilename.equals("pipein")) { // Allow piping graph in
			sharder.shard(System.in, fileType);
		} else {
			if (!new File(ChiFilenames.getFilenameIntervals(baseFilename,
					nShards)).exists()) {
				sharder.shard(new FileInputStream(new File(baseFilename)),
						fileType);
			} else {
				logger.info("Found shards -- no need to preprocess");
			}
		}

		/* Run GraphChi ... */
		GraphChiEngine<Integer, Integer> engine = new GraphChiEngine<Integer, Integer>(
				baseFilename, nShards);
		engine.setEdataConverter(new IntConverter());
		engine.setVertexDataConverter(new IntConverter());
		engine.setEnableScheduler(true);
		engine.run(new SCC(), 10);

	}
}