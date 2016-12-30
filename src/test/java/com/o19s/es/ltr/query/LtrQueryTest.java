package com.o19s.es.ltr.query;

import ciir.umass.edu.learning.*;
import ciir.umass.edu.metric.NDCGScorer;
import ciir.umass.edu.utilities.MyThreadPool;
import com.o19s.es.ltr.query.DenseProgramaticDataPoint;
import com.o19s.es.ltr.query.LtrQuery;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.Field.Store;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by doug on 12/24/16.
 */
@LuceneTestCase.SuppressSysoutChecks(bugUrl = "RankURL does this when training models... ")
public class LtrQueryTest extends LuceneTestCase {

    Field newField(String name, String value, Store stored) {
        FieldType tagsFieldType = new FieldType();
        tagsFieldType.setStored(stored == Store.YES);
        IndexOptions idxOptions = IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS;
        tagsFieldType.setIndexOptions(idxOptions);
        return new Field(name, value, tagsFieldType);
    }

    IndexSearcher searcherUnderTest;
    RandomIndexWriter indexWriterUnderTest;
    IndexReader indexReaderUnderTest;
    Directory dirUnderTest;
    Ranker ltrModel;

    // docs with doc ids array index
    String[] docs = new String[] { "how now brown cow",
                                   "brown is the color of cows",
                                   "brown cow",
                                   "banana cows are yummy"};

    @Before
    public void setupIndex() throws IOException {
        dirUnderTest = newDirectory();

        indexWriterUnderTest = new RandomIndexWriter(random(), dirUnderTest);
        for (int i = 0; i < docs.length; i++) {
            Document doc = new Document();
            doc.add(newStringField("id", "" + i, Field.Store.YES));
            doc.add(newField("field", docs[i], Store.YES));
            indexWriterUnderTest.addDocument(doc);
        }
        indexWriterUnderTest.commit();
        indexWriterUnderTest.forceMerge(1);
        indexWriterUnderTest.flush();


        indexReaderUnderTest = indexWriterUnderTest.getReader();
        searcherUnderTest = newSearcher(indexReaderUnderTest);
        //hardcode the similarity, we expect consisten scoring
        searcherUnderTest.setSimilarity(new ClassicSimilarity());
    }

    public Query[] getFeatures(String userQuery) {
        Query[] features = new Query[] {new TermQuery(new Term("field",  userQuery.split(" ")[0])),
                new PhraseQuery("field", userQuery.split(" "))};
        return features;
    }

    public List<List<Float>> getFeatureScores(String userQuery) throws IOException {
        Query[] features = getFeatures(userQuery);

        ArrayList<List<Float>> featuresPerDoc = new ArrayList<List<Float>>(docs.length);
        // initialize feature outputs
        for (int i = 0; i < docs.length; i++) {
            featuresPerDoc.add(i, new ArrayList<Float>(features.length));
            for (int ftrIdx = 0; ftrIdx < features.length; ftrIdx++ ) {
                featuresPerDoc.get(i).add(ftrIdx, 0.0f);
            }
        }


        int ftrIdx = 0;
        for (Query feature: features) {
            TopDocs topDocs = searcherUnderTest.search(feature, 10);
            ScoreDoc[] scoreDocs = topDocs.scoreDocs;
            for (ScoreDoc scoreDoc: scoreDocs) {
                Document d = searcherUnderTest.doc(scoreDoc.doc);
                String idVal = d.get("id");
                int docId = Integer.decode(idVal);

                featuresPerDoc.get(docId).set(ftrIdx, scoreDoc.score);
            }
            ftrIdx++;
        }
        return featuresPerDoc;

    }

    public List<DataPoint> makeQueryJudgements(int qid,
                                               List<List<Float>> featuresPerDoc,
                                               Float[] relevanceGradesPerDoc) {
        assert(featuresPerDoc.size() == docs.length);
        assert(relevanceGradesPerDoc.length == docs.length);

        List<DataPoint> rVal = new ArrayList<DataPoint>();

        for (int i = 0; i < docs.length; i++) {
            List<Float> featuresForDoc = featuresPerDoc.get(i);

            DataPoint dp = new DenseProgramaticDataPoint(featuresForDoc.size());
            dp.setID(Integer.toString(qid)); /*query ID*/
            dp.setLabel(relevanceGradesPerDoc[i]); /*labeled relevance judgement*/

            // set each feature

            for (int ftrIdx = 0; ftrIdx < featuresForDoc.size(); ftrIdx++) {
                /*RankLib features are 1 based*/
                dp.setFeatureValue(ftrIdx + 1, featuresForDoc.get(ftrIdx));
            }
            rVal.add(i, dp);
        }
        return rVal;
    }


    @Test
    public void testTrainModel() throws IOException {
        //     public LambdaMART(List<RankList> samples, int[] features, MetricScorer scorer) {

        // Each RankList needed for training corresponds to one query,
        // or that apperas how RankLib wants the data
        List<RankList> samples = new ArrayList<RankList>();

        System.out.println("-----------------------");
        System.out.println("TRAINING");

        List<List<Float>> featuresPerDoc = getFeatureScores("brown cow");
        int numFeatures = featuresPerDoc.get(0).size();

        RankList rl = new RankList(makeQueryJudgements(0, featuresPerDoc,
                                                        new Float[] {3.0f, 2.0f, 4.0f, 0.0f}));
        samples.add(rl);

        // each RankList appears to correspond to a
        // query
        RankerTrainer trainer = new RankerTrainer();
        Ranker ranker = trainer.train(/*what type of model ot train*/RANKER_TYPE.LAMBDAMART,
                                      /*The training data*/ samples,
                                      /*which features to use*/new int[] {1,2}
                                      /*how to score ranking*/, new NDCGScorer());
        System.out.println("Model Trained");
        float[] scores = new float[] {(float)ranker.eval(rl.get(0)), (float)ranker.eval(rl.get(1)),
                (float)ranker.eval(rl.get(2)), (float)ranker.eval(rl.get(3))};



        // Ok now lets rerun that as a Lucene Query
        System.out.println("-----------------------");
        System.out.println("QUERYING");
        List<Query> features = Arrays.asList(getFeatures("brown cow"));
        LtrQuery ltrQuery = new LtrQuery(features, ranker);
        TopDocs topDocs = searcherUnderTest.search(ltrQuery, 10);
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;
        assert(scoreDocs.length == docs.length);
        for (ScoreDoc scoreDoc: scoreDocs) {
            Document d = searcherUnderTest.doc(scoreDoc.doc);
            String idVal = d.get("id");
            int docId = Integer.decode(idVal);
            float modelScore = scores[docId];
            float queryScore = scoreDoc.score;
            System.out.printf("Doc Id %d f1 %f f2 %f\n", docId, featuresPerDoc.get(docId).get(0),  featuresPerDoc.get(docId).get(1));
            System.out.printf("Doc Id %d: Model Score %f Query Score %f\n", docId, modelScore, queryScore);
            assertEquals(modelScore, queryScore, 0.01);
        }

        // Try again with a model serialized

        String modelAsStr = ranker.model();
        RankerFactory rankerFactory = new RankerFactory();
        Ranker rankerAgain = rankerFactory.loadRankerFromString(modelAsStr);
        float[] scoresAgain = new float[] {(float)rankerAgain.eval(rl.get(0)), (float)rankerAgain.eval(rl.get(1)),
                (float)rankerAgain.eval(rl.get(2)), (float)rankerAgain.eval(rl.get(3))};

        System.out.println("-----------------------");
        System.out.println("QUERYING");
        features = Arrays.asList(getFeatures("brown cow"));
        ltrQuery = new LtrQuery(features, rankerAgain);
        topDocs = searcherUnderTest.search(ltrQuery, 10);
        scoreDocs = topDocs.scoreDocs;
        assert(scoreDocs.length == docs.length);
        for (ScoreDoc scoreDoc: scoreDocs) {
            Document d = searcherUnderTest.doc(scoreDoc.doc);
            String idVal = d.get("id");
            int docId = Integer.decode(idVal);
            float modelScore = scoresAgain[docId];
            float queryScore = scoreDoc.score;
            System.out.printf("Doc Id %d f1 %f f2 %f\n", docId, featuresPerDoc.get(docId).get(0),  featuresPerDoc.get(docId).get(1));
            System.out.printf("Doc Id %d: Model Score %f Query Score %f\n", docId, modelScore, queryScore);
            assertEquals(modelScore, queryScore, 0.01);
        }

    }


    @After
    public void closeStuff() throws IOException {
        indexReaderUnderTest.close();
        indexWriterUnderTest.close();
        dirUnderTest.close();
        // Ranklib's singleton instance
        MyThreadPool.getInstance().shutdown();
    }



}