import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.logging.Logger;

import org.apache.commons.lang.ArrayUtils;

import edu.cmu.graphchi.ChiEdge;
import edu.cmu.graphchi.ChiFilenames;
import edu.cmu.graphchi.ChiLogger;
import edu.cmu.graphchi.ChiVertex;
import edu.cmu.graphchi.GraphChiContext;
import edu.cmu.graphchi.GraphChiProgram;
import edu.cmu.graphchi.datablocks.BytesToValueConverter;
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

public class SCC {

	private static Logger logger = ChiLogger.getLogger("SCC");

	// 프로그램에서 직접 사용
	public static int superstep = 0;
	public static int CONTRACTED_GRAPH_OUTPUT = 0;
	public static boolean firstIteration = true;
	public static boolean remainingVertices = true;

	// public static GraphChiEngine<SCCInfo, BiDirLabel> engine = null;

	/**
	 * Initialize the sharder-program.
	 * 
	 * @param graphName
	 * @param numShards
	 * @return
	 * @throws java.io.IOException
	 */
	protected static FastSharder<SCCInfo, BiDirLabel> createSharder(
			String graphName, int numShards) throws IOException {
		return new FastSharder<SCCInfo, BiDirLabel>(graphName, numShards,
				new VertexProcessor<SCCInfo>() {
					public SCCInfo receiveVertexValue(int vertexId, String token) {
						return new SCCInfo();
					}
				}, new EdgeProcessor<BiDirLabel>() {
					public BiDirLabel receiveEdge(int from, int to, String token) {
						return new BiDirLabel();
					}
				}, new SCCInfoConverter(), new BiDirLabelConverter());
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
		while (SCC.remainingVertices) {
			System.out.println("Starting Superstep: " + SCC.superstep + "\n");
			SCC.superstep++;
			SCC.remainingVertices = false;

			SCCForward forward = new SCCForward();
			GraphChiEngine<SCCInfo, BiDirLabel> engine = new GraphChiEngine<SCCInfo, BiDirLabel>(
					baseFilename, nShards);
			engine.setVertexDataConverter(new SCCInfoConverter());
			engine.setEdataConverter(new BiDirLabelConverter());
			engine.setEnableScheduler(true);
			// if (SCC.firstIteration) {
			// // Reset vertexData
			// }
			// engine.setSaveEdgefilesAfterInmemmode(true);
			engine.run(forward, 1000);

			if (SCC.remainingVertices) {
				System.out.println("Starting Backward \n");

				SCCBackward backward = new SCCBackward();
				GraphChiEngine<SCCInfo, BiDirLabel> engine2 = new GraphChiEngine<SCCInfo, BiDirLabel>(
						baseFilename, nShards);
				// engine.setSaveEdgefilesAfterInmemmode(true);
				engine2.run(backward, 1000);
				
				int origNumShards = engine2.getIntervals().size();
				
				if (origNumShards > 1) {
					// Contract deleted edges
				}
			}
		}

	}

}

class SCCForward implements GraphChiProgram<SCCInfo, BiDirLabel> {

	@Override
	public void update(ChiVertex<SCCInfo, BiDirLabel> vertex,
			GraphChiContext context) {
		if (SCC.firstIteration) {
			vertex.setValue(new SCCInfo(vertex.getId()));
		}

		if (vertex.getValue().confirmed) {
			VertexUtil.removeAllEdges(vertex);

			return;
		}

		if (vertex.numInEdges() == 0 || vertex.numOutEdges() == 0) {
			if (vertex.numEdges() > 0) {
				vertex.setValue(new SCCInfo(vertex.getId(), true));
			}

			VertexUtil.removeAllEdges(vertex);
			return;
		}

		SCC.remainingVertices = true;

		SCCInfo vertexData = vertex.getValue();
		boolean propagate = false;
		if (context.getIteration() == 0) {
			/*
			 * TODO: 검증 필요 [원본] vertexData = vertex.getId()
			 */
			vertexData = new SCCInfo(vertex.getId());
			propagate = true;

			// Clean up in-edges. This would be nicer in the messaging
			// abstraction...
			for (int i = 0; i < vertex.numInEdges(); i++) {
				BiDirLabel edgeData = vertex.inEdge(i).getValue();
				if (!edgeData.deleted()) {
					edgeData.setMyLabel(vertex.getId(), vertex.inEdge(i)
							.getVertexId(), vertex.getId());
					vertex.inEdge(i).setValue(edgeData);
				}
			}
		} else {
			int minid = vertexData.color;
			for (int i = 0; i < vertex.numInEdges(); i++) {
				if (!vertex.inEdge(i).getValue().deleted()) {
					minid = Math.min(
							minid,
							vertex.inEdge(i)
									.getValue()
									.getNeighborLabel(vertex.getId(),
											vertex.inEdge(i).getVertexId()));
				}
			}

			if (minid != vertexData.color) {
				vertexData.color = minid;
				propagate = true;
			}
		}
		vertex.setValue(vertexData);

		if (propagate) {
			for (int i = 0; i < vertex.numOutEdges(); i++) {
				BiDirLabel edgeData = vertex.outEdge(i).getValue();
				if (!edgeData.deleted()) {
					edgeData.setMyLabel(vertex.getId(), vertex.outEdge(i)
							.getVertexId(), vertexData.color);
					vertex.outEdge(i).setValue(edgeData);
					context.getScheduler().addTask(
							vertex.outEdge(i).getVertexId());
				}
			}
		}
	}

	@Override
	public void beginIteration(GraphChiContext ctx) {
		// TODO Auto-generated method stub

	}

	@Override
	public void endIteration(GraphChiContext ctx) {
		SCC.firstIteration = false;

	}

	@Override
	public void beginInterval(GraphChiContext ctx, VertexInterval interval) {
		// TODO Auto-generated method stub

	}

	@Override
	public void endInterval(GraphChiContext ctx, VertexInterval interval) {
		// TODO Auto-generated method stub

	}

	@Override
	public void beginSubInterval(GraphChiContext ctx, VertexInterval interval) {
		// TODO Auto-generated method stub

	}

	@Override
	public void endSubInterval(GraphChiContext ctx, VertexInterval interval) {
		// TODO Auto-generated method stub

	}

}

class SCCBackward implements GraphChiProgram<SCCInfo, BiDirLabel> {

	@Override
	public void update(ChiVertex<SCCInfo, BiDirLabel> vertex,
			GraphChiContext context) {
		if (vertex.getValue().confirmed) {
			return;
		}

		SCCInfo vertexData = vertex.getValue();
		boolean propagate = false;

		if (context.getIteration() == 0) {
			// "Leader" of the SCC
			if (vertexData.color == vertex.getId()) {
				propagate = true;
				VertexUtil.removeAllEdges(vertex);
			}
		} else {
			// Loop over in-edges and see if there is a match
			boolean match = false;
			for (int i = 0; i < vertex.numOutEdges(); i++) {
				if (!vertex.outEdge(i).getValue().deleted()) {
					if (vertex
							.outEdge(i)
							.getValue()
							.getNeighborLabel(vertex.getId(),
									vertex.outEdge(i).getVertexId()) == vertexData.color) {
						match = true;

						break;
					}
				}
			}

			if (match) {
				propagate = true;
				VertexUtil.removeAllEdges(vertex);
				vertex.setValue(new SCCInfo(vertexData.color, true));
			} else {
				vertex.setValue(new SCCInfo(vertex.getId(), false));
			}
		}

		if (propagate) {
			for (int i = 0; i < vertex.numInEdges(); i++) {
				BiDirLabel edgeData = vertex.inEdge(i).getValue();
				if (!edgeData.deleted()) {
					edgeData.setMyLabel(vertex.getId(), vertex.inEdge(i)
							.getVertexId(), vertexData.color);
					vertex.inEdge(i).setValue(edgeData);
					context.getScheduler().addTask(
							vertex.inEdge(i).getVertexId());
				}
			}
		}

	}

	@Override
	public void beginIteration(GraphChiContext ctx) {
		// TODO Auto-generated method stub

	}

	@Override
	public void endIteration(GraphChiContext ctx) {
		// TODO Auto-generated method stub

	}

	@Override
	public void beginInterval(GraphChiContext ctx, VertexInterval interval) {
		// TODO Auto-generated method stub

	}

	@Override
	public void endInterval(GraphChiContext ctx, VertexInterval interval) {
		// TODO Auto-generated method stub

	}

	@Override
	public void beginSubInterval(GraphChiContext ctx, VertexInterval interval) {
		// TODO Auto-generated method stub

	}

	@Override
	public void endSubInterval(GraphChiContext ctx, VertexInterval interval) {
		// TODO Auto-generated method stub

	}

}

class BiDirLabel implements Serializable {
	private static final long serialVersionUID = -6638107748426892170L;
	public static final int DELETED = -1;
	public int smallerOne;
	public int largerOne;

	public void setNeighborLabel(int myid, int nbid, int newValue) {
		if (myid < nbid) {
			largerOne = newValue;
		} else {
			smallerOne = newValue;
		}
	}

	public Integer getNeighborLabel(int myid, int nbid) {
		if (myid < nbid) {
			return largerOne;
		} else {
			return smallerOne;
		}
	}

	public void setMyLabel(int myid, int nbid, int newValue) {
		if (myid < nbid) {
			smallerOne = newValue;
		} else {
			largerOne = newValue;
		}
	}

	public Integer getMyLabel(int myid, int nbid) {
		if (myid < nbid) {
			return smallerOne;
		} else {
			return largerOne;
		}
	}

	public boolean deleted() {
		return (smallerOne == DELETED);
	}

	@Override
	public String toString() {
		return String.format("%d, %d", smallerOne, largerOne);
	}
}

class BiDirLabelConverter implements BytesToValueConverter<BiDirLabel> {

	@Override
	public int sizeOf() {
		return 8;
	}

	@Override
	public BiDirLabel getValue(byte[] array) {
		IntConverter intConverter = new IntConverter();

		BiDirLabel val = new BiDirLabel();

		byte[] smaller = ArrayUtils.subarray(array, 0, 4);
		val.smallerOne = intConverter.getValue(smaller);

		byte[] larger = ArrayUtils.subarray(array, 4, 8);
		val.largerOne = intConverter.getValue(larger);

		return val;
	}

	@Override
	public void setValue(byte[] array, BiDirLabel val) {
		IntConverter intConverter = new IntConverter();

		byte[] smaller = new byte[4];
		intConverter.setValue(smaller, val.smallerOne);

		byte[] larger = new byte[4];
		intConverter.setValue(larger, val.largerOne);

		array[0] = smaller[0];
		array[1] = smaller[1];
		array[2] = smaller[2];
		array[3] = smaller[3];
		array[4] = larger[0];
		array[5] = larger[1];
		array[6] = larger[2];
		array[7] = larger[3];

	}

}

class SCCInfo {
	public int color;
	public boolean confirmed;

	public int getColor() {
		return color;
	}

	public void setColor(int color) {
		this.color = color;
	}

	public boolean isConfirmed() {
		return confirmed;
	}

	public void setConfirmed(boolean confirmed) {
		this.confirmed = confirmed;
	}

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

	@Override
	public String toString() {
		return String.format("%s, %s", color, confirmed);
	}
}

class SCCInfoConverter implements BytesToValueConverter<SCCInfo> {

	@Override
	public int sizeOf() {
		return 8;
	}

	@Override
	public SCCInfo getValue(byte[] array) {
		IntConverter intConverter = new IntConverter();

		byte[] colorByte = ArrayUtils.subarray(array, 0, 4);
		int color = intConverter.getValue(colorByte);

		byte[] confirmedByte = ArrayUtils.subarray(array, 4, 8);
		boolean confirmed = (intConverter.getValue(confirmedByte) == 0) ? false
				: true;

		return new SCCInfo(color, confirmed);
	}

	@Override
	public void setValue(byte[] array, SCCInfo val) {
		IntConverter intConverter = new IntConverter();

		byte[] colorByte = new byte[4];
		intConverter.setValue(colorByte, val.color);

		byte[] confirmedByte = new byte[4];
		intConverter.setValue(confirmedByte, val.confirmed ? 1 : 0);

		array[0] = colorByte[0];
		array[1] = colorByte[1];
		array[2] = colorByte[2];
		array[3] = colorByte[3];
		array[4] = confirmedByte[0];
		array[5] = confirmedByte[1];
		array[6] = confirmedByte[2];
		array[7] = confirmedByte[3];
	}

}

class VertexUtil {
	public static void removeAllEdges(ChiVertex<SCCInfo, BiDirLabel> vertex) {
		if (vertex.numEdges() > 0) {
			// remove all edges of the vertex
			for (int i = 0; i < vertex.numInEdges(); i++) {
				ChiEdge<BiDirLabel> e = vertex.inEdge(i);
				e.getValue().largerOne = BiDirLabel.DELETED;
				e.getValue().smallerOne = BiDirLabel.DELETED;
			}

			for (int i = 0; i < vertex.numOutEdges(); i++) {
				ChiEdge<BiDirLabel> e = vertex.outEdge(i);
				e.getValue().largerOne = BiDirLabel.DELETED;
				e.getValue().smallerOne = BiDirLabel.DELETED;
			}
		}
	}
}