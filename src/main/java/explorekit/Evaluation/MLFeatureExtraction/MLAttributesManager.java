package explorekit.Evaluation.MLFeatureExtraction;

import com.sun.org.apache.xml.internal.resolver.readers.ExtendedXMLCatalogReader;
import explorekit.Evaluation.WrapperEvaluation.WrapperEvaluator;
import explorekit.data.Column;
import explorekit.data.ColumnInfo;
import explorekit.data.Dataset;
import explorekit.data.DiscreteColumn;
import explorekit.operators.Operator;
import explorekit.operators.OperatorAssignment;
import explorekit.operators.OperatorsAssignmentsManager;
import weka.classifiers.Classifier;
import weka.classifiers.evaluation.Evaluation;
import weka.classifiers.trees.J48;
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.core.converters.ArffLoader;
import weka.core.converters.ArffSaver;

import javax.xml.crypto.Data;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

import static explorekit.operators.OperatorsAssignmentsManager.getUnaryOperatorsList;

/**
 * Created by giladkatz on 07/05/2016.
 */
public class MLAttributesManager {

    /**
     * Receives a dataset object and generates an Instance object that contains a set of attributes for EACH candidate
     * attribute.
     * @param dataset
     * @return
     * @throws Exception
     */
    public Instances getDatasetInstances(Dataset dataset, Properties properties) throws Exception {

        if (properties == null) {
            properties = new Properties();
            InputStream input = this.getClass().getClassLoader().getResourceAsStream("config.properties");
            properties.load(input);
        }

        String fileName = dataset.getName() + "_candidateAttributesData" + ".ser";
        String filePath = properties.getProperty("DatasetInstancesFilesLocation") + fileName;

        File file = new File(filePath);
        if (file.exists()) {
            FileInputStream streamIn = new FileInputStream(filePath);
            ObjectInputStream objectinputstream = new ObjectInputStream(streamIn);
            try {
                Instances datasetInstances = (Instances) objectinputstream.readObject();
                return datasetInstances;
            }
            catch (Exception ex) {
                System.out.println("Error reading Instances from file: " + dataset.getName() + "  :  " + ex.getMessage());
            }
            return null;
        }
        else {
            List<HashMap<Integer,AttributeInfo>> datasetAttributeValues = generateTrainingSetDataseAttributes(dataset, properties);
            Instances datasetInstances = generateValuesMatrix(datasetAttributeValues);
            FileOutputStream fout = new FileOutputStream(properties.getProperty("DatasetInstancesFilesLocation") + fileName, true);
            ObjectOutputStream oos = new ObjectOutputStream(fout);
            oos.writeObject(datasetInstances);

            ArffSaver saver = new ArffSaver();
            saver.setInstances(datasetInstances);
            saver.setFile(new File(properties.getProperty("DatasetInstancesFilesLocation") + dataset.getName() + "_candidateAttributesData" + ".arff"));
            saver.writeBatch();

            return datasetInstances;
        }
    }

    /**
     * Generates the "background" model that will be used to classifiy the candidate attributes of the provided dataset.
     * The classification model will be generated by combining the information gathered FROM ALL OTHER DATASETS.
     * @param dataset
     * @return
     */
    public Classifier generateBackgroundClassificationModel(Dataset dataset, Properties properties) throws Exception {
        String backgroundFilePath = properties.getProperty("backgroundClassifierLocation") + "_background_" + dataset.getName() + "_classifier_obj";
        Path path = Paths.get(backgroundFilePath);

        //If the classification model already exists, load and return it
        if (Files.exists(path)) {
            FileInputStream streamIn = new FileInputStream(backgroundFilePath);
            ObjectInputStream objectinputstream = new ObjectInputStream(streamIn);
            try {
                Classifier backgroundModel = (Classifier) objectinputstream.readObject();
                objectinputstream.close();
                return backgroundModel;
            }
            catch (Exception ex) {
                System.out.println("Error reading Instances from file: " + dataset.getName() + "  :  " + ex.getMessage());
                return null;
            }
        }
        //Otherwise, generate, save and return it (WARNING - takes time)
        else {
            File folder = new File(properties.getProperty("DatasetInstancesFilesLocation"));
            File[] filesArray = folder.listFiles();

            boolean addHeader = true;
            for (int i = 0; i < filesArray.length; i++) {
            //for (int i = 0; i < 1; i++) {
                //we're making sure that the analyzed item is both a file AND not generated from the dataset we're currently analyzing
                if (filesArray[i].isFile() && !filesArray[i].getName().contains(dataset.getName()) && filesArray[i].getPath().contains(".arff")) {
                    addArffFileContentToTargetFile(backgroundFilePath, filesArray[i].getAbsolutePath(), addHeader);
                    addHeader = false;
                }
                else {
                    System.out.println("skipping file: " + filesArray[i].getName());
                }
            }
            //now we load the contents of the ARFF file into an Instances object and train the classifier
            BufferedReader reader = new BufferedReader(new FileReader(backgroundFilePath + ".arff"));
            //Instances data = new Instances(reader);
            //reader.close();

            ArffLoader.ArffReader arffReader = new ArffLoader.ArffReader(reader);
            //Instances structure = arffReader.getStructure();
            Instances data = arffReader.getData();

            data.setClassIndex(data.numAttributes() - 1);

            //the chosen classifier
            RandomForest classifier = new RandomForest();
            classifier.setNumExecutionSlots(Integer.parseInt(properties.getProperty("numOfThreads")));

            classifier.buildClassifier(data);

            File file = new File(backgroundFilePath + ".arff");
            file.delete();

            //now we write the classifier to file prior to returning the object
            FileOutputStream out = new FileOutputStream(backgroundFilePath);
            ObjectOutputStream oout = new ObjectOutputStream(out);
            oout.writeObject(classifier);
            oout.flush();
            oout.close();

            return classifier;
        }
    }

    /**
     * Appends the content of an ARFF file to the end of another. Used to generate the all-but-one ARFF files
     * @param targetFilePath The path of the file to which we want to write
     * @param arffFilePath the source file
     * @param addHeader whether the ARFF header needs to be copied as well. When this is set to 'true' the file will be overwritten
     * @throws Exception
     */
    public void addArffFileContentToTargetFile(String targetFilePath, String arffFilePath, boolean addHeader) throws Exception {
        targetFilePath += ".arff";
        BufferedReader br = new BufferedReader(new FileReader(arffFilePath));
        BufferedWriter bw = new BufferedWriter(new FileWriter(targetFilePath, !addHeader));
        String line;
        boolean foundData = false;
        while ((line = br.readLine()) != null) {
            if (addHeader) {
                bw.write(line + "\n");
            }
            else {
                if (!foundData && line.toLowerCase().contains("@data")) {
                    foundData = true;
                    line = br.readLine();
                }
                if (foundData) {
                    bw.write(line + "\n");
                }
            }
        }
        bw.flush();
        bw.close();
    }


    /**
     * An overload, used to apply the generateValuesMatrix function for a single sample
     * @param datasetAttributeValues
     * @return
     * @throws Exception
     */
    public Instances generateValuesMatrix(HashMap<Integer,AttributeInfo> datasetAttributeValues) throws Exception {
        List<HashMap<Integer,AttributeInfo>> tempList = new ArrayList<>();
        tempList.add(datasetAttributeValues);
        return generateValuesMatrix(tempList);
    }

    /**
     * Creates a data matrix from a HashMap of AttributeInfo objects.
     * @param datasetAttributeValues
     * @return
     */
    public Instances generateValuesMatrix(List<HashMap<Integer,AttributeInfo>> datasetAttributeValues) throws Exception {
        ArrayList<Attribute> attributes = generateAttributes(datasetAttributeValues.get(0));


        Instances finalSet = new Instances("trainingSet", attributes, 0);
        for (HashMap<Integer,AttributeInfo> attValues : datasetAttributeValues) {
            double[] row = new double[datasetAttributeValues.get(0).size()];
            for (int key : attValues.keySet()) {
                AttributeInfo att = attValues.get(key);
                if (att.getAttributeType() == Column.columnType.Numeric) {
                    try {
                        Object ob = attValues.get(key).getValue();
                        if(ob instanceof Integer) {
                            row[key] = ((Integer) attValues.get(key).getValue());
                        }
                        else {
                            row[key] = ((Double) attValues.get(key).getValue());
                        }
                    }
                    catch (Exception ex) {
                        int x=5;
                    }
                }
                else {
                    row[key] = (Integer) attValues.get(key).getValue();
                }
            }
            DenseInstance di = new DenseInstance(1.0, row);
            finalSet.add(di);
        }
        return finalSet;
    }

    /**
     * Generates a list of Attribute objects. The types of the Attributes are in the same order as the types we
     * want to add the to Instances object.
     * @param attributesData
     * @return
     * @throws Exception
     */
    private ArrayList<Attribute> generateAttributes(HashMap<Integer,AttributeInfo> attributesData) throws Exception {
        ArrayList<Attribute> listToReturn = new ArrayList<>();
        for (int i=0; i<attributesData.size(); i++) {
            AttributeInfo tmpAtt = attributesData.get(i);
            Attribute att = null;
            switch(tmpAtt.getAttributeType())
            {
                case Numeric:
                    att = new Attribute(Integer.toString(i),i);
                    break;
                case Discrete:
                    List<String> values = new ArrayList<>();
                    int numOfDiscreteValues = tmpAtt.getNumOfDiscreteValues();
                    for (int j=0; j<numOfDiscreteValues; j++) { values.add(Integer.toString(j)); }
                    att = new Attribute(Integer.toString(i), values, i);
                    break;
                case String:
                    //Most classifiers can't handle Strings. Currently we don't include them in the dataset
                    break;
                case Date:
                    //Currently we don't include them in the dataset. We don't have a way of handling "raw" dates
                    break;
                default:
                    throw new Exception("unsupported column type");
            }
            if (att != null) {
                listToReturn.add(att);
            }
        }

        return listToReturn;
    }

    /**
     * Used to generate the attributes of a dataset that will be used to train the classifier for the attribute selection
     * (i.e. not the dataset that is currently being analyzed)
     * @param dataset
     * @return
     */
    public List<HashMap<Integer,AttributeInfo>> generateTrainingSetDataseAttributes(Dataset dataset, Properties properties) throws Exception {
        List<HashMap<Integer,AttributeInfo>> candidateAttributesList = new ArrayList<>();
        String[] classifiers = properties.getProperty("classifiersForMLAttributesGeneration").split(",");

        //obtaining the attributes for the dataset itself is straightforward
        DatasetBasedAttributes dba = new DatasetBasedAttributes();
        for (String classifier : classifiers) {

            //For each dataset and classifier combination, we need to get the results on the "original" dataset so we can later compare
            Evaluation evaluationResults = runClassifier(classifier, dataset.generateSet(true), dataset.generateSet(false), properties);
            double originalAuc = CalculateAUC(evaluationResults, dataset);

            //Generate the dataset attributes
            HashMap<Integer, AttributeInfo> datasetAttributes = dba.getDatasetBasedFeatures(dataset, classifier, properties);

            //Add the identifier of the classifier that was used

            AttributeInfo classifierAttribute = new AttributeInfo("Classifier", Column.columnType.Discrete, getClassifierIndex(classifier), 3);
            datasetAttributes.put(datasetAttributes.size(), classifierAttribute);


            //now we need to generate the candidate attributes and evaluate them. This requires a few preliminary steps:
            // 1) Replicate the dataset and create the discretized features and add them to the dataset
            OperatorsAssignmentsManager oam = new OperatorsAssignmentsManager(properties);
            List<Operator> unaryOperators = oam.getUnaryOperatorsList();
            //The unary operators need to be evaluated like all other operator assignments (i.e. attribtues generation)
            List<OperatorAssignment> unaryOperatorAssignments = oam.getOperatorAssignments(dataset, null, unaryOperators, Integer.parseInt(properties.getProperty("maxNumOfAttsInOperatorSource")));
            Dataset replicatedDataset = generateDatasetReplicaWithDiscretizedAttributes(dataset, unaryOperatorAssignments, oam);

            // 2) Obtain all other operator assignments (non-unary). IMPORTANT: this is applied on the REPLICATED dataset so we can take advantage of the discretized features
            List<Operator> nonUnaryOperators = oam.getNonUnaryOperatorsList();
            List<OperatorAssignment> nonUnaryOperatorAssignments = oam.getOperatorAssignments(replicatedDataset, null, nonUnaryOperators, Integer.parseInt(properties.getProperty("maxNumOfAttsInOperatorSource")));

            // 3) Generate the candidate attribute and generate its attributes
            nonUnaryOperatorAssignments.addAll(unaryOperatorAssignments);

            //oaList.parallelStream().forEach(oa -> {
            int counter = 0;
            //for (OperatorAssignment oa : nonUnaryOperatorAssignments) {
            ReentrantLock wrapperResultsLock = new ReentrantLock();
            nonUnaryOperatorAssignments.parallelStream().forEach(oa -> {
                try {
                    OperatorsAssignmentsManager oam1 = new OperatorsAssignmentsManager(properties);
                    Dataset datasetReplica = dataset.replicateDataset();
                    ColumnInfo candidateAttribute = null;
                    try {
                        candidateAttribute = oam1.generateColumn(datasetReplica, oa, true);
                    }
                    catch (Exception ex) {
                        candidateAttribute = oam1.generateColumn(datasetReplica, oa, true);
                    }


                    OperatorAssignmentBasedAttributes oaba = new OperatorAssignmentBasedAttributes();
                    HashMap<Integer, AttributeInfo> candidateAttributes = oaba.getOperatorAssignmentBasedAttributes(dataset, oa, candidateAttribute, properties);

                    datasetReplica.addColumn(candidateAttribute);
                    Evaluation evaluationResults1 = runClassifier(classifier, datasetReplica.generateSet(true), datasetReplica.generateSet(false), properties);

                    double auc = CalculateAUC(evaluationResults1, datasetReplica);
                    double deltaAuc = auc - originalAuc;
                    AttributeInfo classAttrubute;
                    if (deltaAuc > 0.01) {
                        classAttrubute = new AttributeInfo("classAttribute", Column.columnType.Discrete, 1,2);
                        System.out.println("found positive match");
                    } else {
                        classAttrubute = new AttributeInfo("classAttribute", Column.columnType.Discrete, 0,2);
                    }

                    //finally, we need to add the dataset attribtues and the class attribute
                    for (AttributeInfo datasetAttInfo : datasetAttributes.values()) {
                        candidateAttributes.put(candidateAttributes.size(), datasetAttInfo);
                    }

                    candidateAttributes.put(candidateAttributes.size(), classAttrubute);
                    wrapperResultsLock.lock();
                    candidateAttributesList.add(candidateAttributes);
                    wrapperResultsLock.unlock();
                }
                catch (Exception ex) {
                    System.out.println("Error in ML features generation : " + oa.getName() + "  :  " + ex.getMessage());
                }
            });
        }

        return candidateAttributesList;
    }


    public Evaluation runClassifier(String classifierName, Instances trainingSet, Instances testSet, Properties properties) throws Exception {
        try {
            OperatorsAssignmentsManager oam = new OperatorsAssignmentsManager(properties);
            Classifier classifier = oam.getClassifier(classifierName);
            classifier.buildClassifier(trainingSet);
            Evaluation evaluation;
            evaluation = new Evaluation(trainingSet);
            evaluation.evaluateModel(classifier, testSet);

            return evaluation;
        }
        catch (Exception ex) {
            System.out.println("problem running classifier");
        }

        return null;
    }

    public double CalculateAUC(Evaluation evaluation, Dataset dataset) {
        double auc = 0;
        for (int i=0; i<dataset.getNumOfClasses(); i++) {
            auc += evaluation.areaUnderROC(i);
        }
        auc = auc/dataset.getNumOfClasses();
        return auc;
    }


    /**
     * Replcates the provided dataset and created discretized versions of all relevant columns and adds them to the datast
     * @param dataset
     * @param unaryOperatorAssignments
     * @param oam
     * @return
     * @throws Exception
     */
    private Dataset generateDatasetReplicaWithDiscretizedAttributes(Dataset dataset,  List<OperatorAssignment> unaryOperatorAssignments, OperatorsAssignmentsManager oam) throws Exception {
        Dataset replicatedDatast = dataset.replicateDataset();
        for (OperatorAssignment oa : unaryOperatorAssignments) {
            ColumnInfo ci = oam.generateColumn(replicatedDatast, oa, true);
            replicatedDatast.addColumn(ci);
        }
        return replicatedDatast;
    }

    /**
     * Returns an integer that is used to represent the classifier in the generated Instances object
     * @param classifier
     * @return
     */
    public int getClassifierIndex(String classifier) throws Exception {
        switch (classifier) {
            case "J48":
                return 0;
            case "SVM":
                return 1;
            case "RandomForest":
                return 2;
            default:
                throw new Exception("Unidentified classifier");
        }
    }
}
