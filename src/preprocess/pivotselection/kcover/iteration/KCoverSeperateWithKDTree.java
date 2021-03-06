package preprocess.pivotselection.kcover.iteration;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.Mapper.Context;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;

import metricspace.IMetric;
import metricspace.IMetricSpace;
import metricspace.MetricSpaceUtility;
import metricspace.Record;
import preprocess.pivotselection.SQConfig;
import preprocess.pivotselection.kcenter.KCenterSeperate;
import preprocess.pivotselection.kcenter.KCenterSeperate.KCenterSeperateMapper;
import preprocess.pivotselection.kcenter.KCenterSeperate.KCenterSeperateReducer;
import preprocess.pivotselection.kcover.DataPoint;
import preprocess.pivotselection.kcover.PointCluster;
import util.kdtree.KDTree;

public class KCoverSeperateWithKDTree {
	public static class KCoverFirstMapper extends Mapper<LongWritable, Text, IntWritable, Text> {
		/**
		 * The dimension of data (set by user, now only support dimension of 2,
		 * if change to 3 or more, has to change some codes)
		 */
		private static int num_dims = 2;
		/**
		 * number of small cells per dimension: when come with a node, map to a
		 * range (divide the domain into small_cell_num_per_dim) (set by user)
		 */
		public static int partitionNum = KCoverDriver.currentNumPartitions;

		private static int samplingPercentage = 10;
		public static int increIndex = 0;

		@Override
		public void setup(Context context) throws IOException, InterruptedException {
			Configuration conf = context.getConfiguration();
			num_dims = conf.getInt(SQConfig.strDimExpression, 2);
			samplingPercentage = conf.getInt(SQConfig.samplingPercentage, 10);
			System.out.println("JOB Name:" + context.getJobName());
			String[] splits = context.getJobName().split(" ");
			int jobId = Integer.parseInt(splits[splits.length - 1]);
			System.out.println("JOB ID:" + jobId);
			// compute partition number based on total number of points, pivot
			// number, pointsPerpartition and job id
			long currentTotalPoint = conf.getLong(SQConfig.strDatasetSize, 30000000);
			int pointPerPartition = conf.getInt(SQConfig.strNumOfPartitions, 10000);
			int pivotCount = conf.getInt(SQConfig.strNumOfPivots, 500);
			int currentNumPartitions = (int) (currentTotalPoint / pointPerPartition);
			for (int i = 0; i < jobId; i++) {
				currentTotalPoint = currentNumPartitions * pivotCount;
				currentNumPartitions = (int) (currentTotalPoint / pointPerPartition);
			}
			partitionNum = currentNumPartitions;
			System.out.println("Current Number of Partitions: " + partitionNum);
		}

		/**
		 * Mapper -2 : Randomly select partitions for each point
		 */
		public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
			int id = increIndex % samplingPercentage;
			increIndex++;
			if (id == 0) {
				Text dat = new Text(value.toString());
				Random r = new Random();
				IntWritable key_id = new IntWritable(r.nextInt(partitionNum));
				context.write(key_id, dat);
			}
		}
	} // end mapper

	public static class KCoverSeperateMapper extends Mapper<LongWritable, Text, IntWritable, Text> {
		/**
		 * The dimension of data (set by user, now only support dimension of 2,
		 * if change to 3 or more, has to change some codes)
		 */
		private static int num_dims = 2;
		/**
		 * number of small cells per dimension: when come with a node, map to a
		 * range (divide the domain into small_cell_num_per_dim) (set by user)
		 */
		public static int partitionNum = KCoverDriver.currentNumPartitions;

		@Override
		public void setup(Context context) throws IOException, InterruptedException {
			Configuration conf = context.getConfiguration();
			num_dims = conf.getInt(SQConfig.strDimExpression, 2);
			System.out.println("JOB Name:" + context.getJobName());
			String[] splits = context.getJobName().split(" ");
			int jobId = Integer.parseInt(splits[splits.length - 1]);
			System.out.println("JOB ID:" + jobId);
			// compute partition number based on total number of points, pivot
			// number, pointsPerpartition and job id
			long currentTotalPoint = conf.getLong(SQConfig.strDatasetSize, 30000000);
			int pointPerPartition = conf.getInt(SQConfig.strNumOfPartitions, 10000);
			int pivotCount = conf.getInt(SQConfig.strNumOfPivots, 500);
			int currentNumPartitions = (int) (currentTotalPoint / pointPerPartition);
			for (int i = 0; i < jobId; i++) {
				currentTotalPoint = currentNumPartitions * pivotCount;
				currentNumPartitions = (int) (currentTotalPoint / pointPerPartition);
			}
			partitionNum = currentNumPartitions;
			System.out.println("Current Number of Partitions: " + partitionNum);
		}

		/**
		 * Mapper -2 : Randomly select partitions for each point
		 */
		public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
			Text dat = new Text(value.toString());
			Random r = new Random();
			IntWritable key_id = new IntWritable(r.nextInt(partitionNum));
			context.write(key_id, dat);
		}
	} // end mapper

	/**
	 * @author yizhouyan
	 *
	 */
	public static class KCoverSeperateReducer extends Reducer<IntWritable, Text, NullWritable, Text> {
		/**
		 * The dimension of data (set by user, now only support dimension of 2,
		 * if change to 3 or more, has to change some codes)
		 */
		private static int num_dims = 2;
		/** number of pivots */
		private static int numOfPivots;
		/** matrix space: text, vector */
		private IMetricSpace metricSpace = null;
		private IMetric metric = null;
		/** The domains. (set by user) */
		private static double domainRange;

		/**
		 * get MetricSpace and metric from configuration
		 * 
		 * @param conf
		 * @throws IOException
		 */
		private void readMetricAndMetricSpace(Configuration conf) throws IOException {
			try {
				metricSpace = MetricSpaceUtility.getMetricSpace(conf.get(SQConfig.strMetricSpace));
				metric = MetricSpaceUtility.getMetric(conf.get(SQConfig.strMetric));
				metricSpace.setMetric(metric);
			} catch (InstantiationException e) {
				throw new IOException("InstantiationException");
			} catch (IllegalAccessException e) {
				e.printStackTrace();
				throw new IOException("IllegalAccessException");
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
				throw new IOException("ClassNotFoundException");
			}
		}

		@Override
		public void setup(Context context) throws IOException, InterruptedException {
			Configuration conf = context.getConfiguration();
			num_dims = conf.getInt(SQConfig.strDimExpression, -1);
			readMetricAndMetricSpace(conf);
			numOfPivots = conf.getInt(SQConfig.strNumOfPivots, 100);
			double domainsMin = conf.getDouble(SQConfig.strDomainMin, 0.0);
			double domainsMax = conf.getDouble(SQConfig.strDomainMax, 10001);
			domainRange = domainsMax - domainsMin;
		}

		public void reduce(IntWritable key, Iterable<Text> values, Context context)
				throws IOException, InterruptedException {

			ArrayList<DataPoint> pointList = new ArrayList<DataPoint>();
			KDTree searchListTree = new KDTree(num_dims);
			// read in points
			for (Text oneValue : values) {
				String sCurrentLine = oneValue.toString();
				Object obj = metricSpace.readObject(sCurrentLine, num_dims);
				DataPoint currentPoint = new DataPoint(obj);
				PointCluster currentCluster = new PointCluster(currentPoint);
				currentPoint.setCluster(currentCluster);
				pointList.add(currentPoint);
				searchListTree.insert(((Record) currentPoint.getPointRecord()).getValueDouble(), currentPoint);
			}
			int treeSize = searchListTree.getTreeSize();
			// System.out.println("Point size: " + treeSize);

			if (pointList.size() <= numOfPivots) {
				// output all points
				for (DataPoint r : pointList) {
					context.write(NullWritable.get(), new Text(r.getPointRecord().toString()));
				}
				return;
			}
			double radius = Math.pow((Math.pow(domainRange, num_dims) / treeSize), 1.0 / num_dims) / 2;

			while (treeSize > numOfPivots) {
				int maxNumCoverPoint = 0;
				ArrayList<Object> maxCoverDataPointInSearchingList = new ArrayList<Object>();
				int iStarPointIndex = -1;

				for (int i = 0; i < pointList.size(); i++) {
					double[] tempLowk = new double[num_dims];
					double[] tempUppk = new double[num_dims];
					double[] pointCoordinate = ((Record) pointList.get(i).getPointRecord()).getValueDouble();
					for (int j = 0; j < num_dims; j++) {
						tempLowk[j] = Math.max(0, pointCoordinate[j] - radius);
						tempUppk[j] = Math.min(domainRange, pointCoordinate[j] + radius);
					}
					Object[] tempCoverList = searchListTree.range(tempLowk, tempUppk);
					int countCover = Math.min(tempCoverList.length - 1, treeSize - numOfPivots);
					if (countCover > maxNumCoverPoint) {
						maxNumCoverPoint = countCover;
						maxCoverDataPointInSearchingList.clear();
						maxCoverDataPointInSearchingList.addAll(Arrays.asList(tempCoverList));
						iStarPointIndex = i;
					}
				} // end for

				if (iStarPointIndex != -1) { // have clusters to merge
					// merge all clusters into one
					DataPoint newCenter = pointList.get(iStarPointIndex);
					HashMap<Integer, DataPoint> newPointList = new HashMap<Integer, DataPoint>();
					newPointList.putAll(newCenter.getCluster().getPointList());
					for (Object point : maxCoverDataPointInSearchingList) {
						newPointList.putAll(((DataPoint) point).getCluster().getPointList());
					}
					PointCluster newCluster = new PointCluster(newCenter, newPointList);
					// update all points in the cluster: point to the
					// newcluster,
					// and remove points from the searchList
					newCenter.setCluster(newCluster);
					// System.out.println("Delete count: " +
					// newPointList.size());
					for (DataPoint pointId : newPointList.values()) {
						pointId.setCluster(newCluster);
						try {
							searchListTree.delete(((Record) pointId.getPointRecord()).getValueDouble());
						} catch (RuntimeException e) {
						}
					}
					searchListTree.insert(((Record) newCenter.getPointRecord()).getValueDouble(), newCenter);
					treeSize = searchListTree.getTreeSize();
					// treeSize = searchListTree.range(lowk, uppk).length;
					// System.out.println(treeSize);
					radius = Math.pow((Math.pow(domainRange, num_dims) / treeSize), 1.0 / num_dims) / 2;
				} else
					radius = radius * 2;
				// break;
			}

			double[] lowk = new double[num_dims];
			double[] uppk = new double[num_dims];
			for (int i = 0; i < num_dims; i++) {
				lowk[i] = 0;
				uppk[i] = domainRange;
			}

			Object[] finalResult = searchListTree.range(lowk, uppk);
			for (int i = 0; i < finalResult.length; i++) {
				context.write(NullWritable.get(), new Text(
						((DataPoint) finalResult[i]).getCluster().getCentralPoint().getPointRecord().toString()));

			} // end for
		}

	} // end reducer

	public static class KCoverFinalRoundMapper extends Mapper<LongWritable, Text, IntWritable, Text> {
		public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
			Text dat = new Text(value.toString());
			context.write(new IntWritable(1), dat);
		}
	}
}
