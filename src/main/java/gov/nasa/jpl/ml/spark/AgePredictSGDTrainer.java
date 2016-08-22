/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gov.nasa.jpl.ml.spark.authorage;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import scala.Tuple2;

import opennlp.tools.util.TrainingParameters;

import org.apache.spark.sql.SparkSession;
import org.apache.spark.api.java.JavaDoubleRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.ml.linalg.SparseVector;
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.mllib.linalg.Vector;

import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.types.*;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.Dataset;
import org.apache.spark.mllib.regression.LassoModel;
import org.apache.spark.mllib.regression.LassoWithSGD;
import org.apache.spark.mllib.regression.LabeledPoint;

import org.apache.spark.ml.feature.CountVectorizer;
import org.apache.spark.ml.feature.CountVectorizerModel;


import org.apache.commons.io.FileUtils;

/**
 * TODO: Documentation
 */
public class AgePredictSGDTrainer {

    public static final String CUTOFF_PARAM = "Cutoff";
    public static final int CUTOFF_DEFAULT = 5;

    public static final String ITERATIONS_PARAM = "Iterations";
    public static final int ITERATIONS_DEFAULT = 100;

    public static void generateEvents(SparkSession spark, String dataIn, 
				      String tokenizer, String featureGenerators,
				      String outDir) throws IOException {
	
	AgeClassifyContextGeneratorWrapper wrapper = new AgeClassifyContextGeneratorWrapper(
	    tokenizer, featureGenerators);
	
	JavaRDD<String> data = spark.sparkContext().textFile(dataIn, 48).toJavaRDD()
	    .cache();
	
	JavaRDD<EventWrapper> samples = data.map(new CreateEvents(wrapper)).cache();
	
	JavaRDD<EventWrapper> validSamples = samples.filter(
	    new Function<EventWrapper, Boolean>() {
		@Override
		public Boolean call(EventWrapper s) { 
		    if (s != null) {
			return s.getValue() != null;
		    }
		    return false; 
		}
	    }).repartition(8);
	
	File dir = new File(outDir);
	if (dir.exists()) {
	    FileUtils.cleanDirectory(dir); //clean out directory (this is optional -- but good know)
	    FileUtils.forceDelete(dir); //delete directory
	}
	
	validSamples.saveAsTextFile(outDir);
    }
    
    private static int getCutoff(Map<String, String> params) {
	
	String cutoffString = params.get(CUTOFF_PARAM);
	
	if (cutoffString != null)
	    return Integer.parseInt(cutoffString);
	else
	    return CUTOFF_DEFAULT;
    }

    private static int getIterations(Map<String, String> params) {

	String iterationString = params.get(ITERATIONS_PARAM);
	
	if (iterationString != null)
	    return Integer.parseInt(iterationString);
	else
	    return ITERATIONS_DEFAULT;
    }

    public static AgePredictModel createModel(String languageCode, SparkSession spark, 
					      String eventDir, AgeClassifyContextGeneratorWrapper wrapper,
					      TrainingParameters trainParams) throws IOException {
	
	Map<String, String> params = trainParams.getSettings();
	
	int cutoff = getCutoff(params);
	int iterations = getIterations(params);
	
	JavaRDD<String> data = spark.sparkContext().textFile(eventDir, 8).toJavaRDD()
	    .cache();
	
	JavaRDD<Row> samples = data.map(
	    new Function<String, Row>() {
		public Row call(String s) {
		    String[] parts = s.split(",");
		    
		    if (parts[0] != "-1") {
			Integer value = Integer.parseInt(parts[0]);
			
			String[] text = parts[2].split(" ");
			//add in the category as another feature
			List<String> tokens= new ArrayList<String>(Arrays.asList(text));
			tokens.add("cat=" + parts[1]);
						    
			return RowFactory.create(value, tokens.toArray());
		    } else {
			return null;
		    }
		}
	    }).cache();
	
	JavaRDD<Row> validSamples = samples.filter(
	    new Function<Row, Boolean>() {
                @Override
                public Boolean call(Row s) { return s != null; }
	    }).cache();
	
	samples.unpersist();
	
	StructType schema = new StructType(new StructField [] {
                new StructField("value", DataTypes.IntegerType, false, Metadata.empty()),
		new StructField("context", new ArrayType(DataTypes.StringType, true), false, Metadata.empty())
            });

	Dataset<Row> eventDF = spark.createDataFrame(validSamples, schema).cache();
	
	CountVectorizerModel cvm = new CountVectorizer()
	    .setInputCol("context")
	    .setOutputCol("feature")
	    .setMinDF(cutoff)
	    .fit(eventDF);
	
	JavaRDD<Row> events = cvm.transform(eventDF).select("value", "feature").javaRDD()
	    .cache();
	eventDF.unpersist();
	
	JavaRDD<LabeledPoint> parsedData = events.map(
	    new Function<Row, LabeledPoint>() {
		public LabeledPoint call(Row r) {
		    Integer val = r.getInt(0);
		    SparseVector vec = (SparseVector) r.get(1);
		    
		    Vector features = Vectors.sparse(vec.size(), vec.indices(), vec.values());
		    return new LabeledPoint(val, features);
		}
	    });
	parsedData.cache();
	
	double stepSize = 1.0;
	double regParam = 0.01;
	final LassoModel model =
	    LassoWithSGD.train(JavaRDD.toRDD(parsedData), iterations, stepSize, regParam);
	
	// Evaluate model on training examples and compute training error
	JavaRDD<Tuple2<Double, Double>> valuesAndPreds = parsedData.map(
	    new Function<LabeledPoint, Tuple2<Double, Double>>() {
		public Tuple2<Double, Double> call(LabeledPoint point) {
		    double prediction = model.predict(point.features());
		    return new Tuple2<>(prediction, point.label());
		}
	    }).cache();
	
	double MSE = new JavaDoubleRDD(valuesAndPreds.map(
	   new Function<Tuple2<Double, Double>, Object>() {
	       public Object call(Tuple2<Double, Double> pair) {
		   return Math.pow(pair._1() - pair._2(), 2.0);
	       }
	   }).rdd()).mean();
	
	System.out.println("training Mean Squared Error = " + MSE);
	
	Map<String, String> manifestInfoEntries = new HashMap<String, String>();
	return new AgePredictModel(languageCode, model, cvm.vocabulary(), wrapper);
    }
}