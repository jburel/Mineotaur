/*
 * Mineotaur: a visual analytics tool for high-throughput microscopy screens
 * Copyright (C) 2014  Bálint Antal (University of Cambridge)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mineotaur.importer;

import javassist.*;
import javassist.NotFoundException;
import org.mineotaur.application.Mineotaur;
import org.mineotaur.common.FileUtil;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.factory.GraphDatabaseBuilder;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.tooling.GlobalGraphOperations;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Class containing fields and methods to generate a graph database from the input provided by the user.
 * @author Bálint Antal
 * @version 0.5b
 */
public class DatabaseGenerator {

    private static final String HIT = "HIT";
    private static final String NUMBER = "NUMBER";
    private static final String ID = "ID";
    private static final String JAVA_LANG_STRING = "java.lang.String";
    private static final String FILTER = "FILTER";
    private static final String COLLECTED = "COLLECTED";
    private static final String FILE_SEPARATOR = File.separator;
    private static final String CONF = "conf";
    private static final String DB = "db";
    private ResourceBundle properties;
    private String prop;
    private String name;
    private String separator;
    private String path;
    private String dbPath;
    private String group;
    private String descriptive;
    private String groupName;
    private List<String> unique;
    private GraphDatabaseService db;
    private GlobalGraphOperations ggo;
    private Map<String, Label> labels = new HashMap<>();
    private Map<String, List<Node>> nonUniqueNodes = new HashMap<>();
    private Map<String, Map<String, RelationshipType>> relationships = new HashMap<>();
    private Map<String, Class> classes = new HashMap<>();
    private Map<Label, List<Object>> stored = new HashMap<>();
    private String[] header;
    private String[] nodeTypes;
    private String[] dataTypes;
    private List<Integer> filters = new ArrayList<>();
    private Map<String, List<Integer>> signatures;
    private Map<String, Label> hits;
    private List<Integer> numericData;
    private Map<String, List<String>> ids;
    private Set<String> keySet;
    private Label groupLabel;
    private Label descriptiveLabel;
    private Label wildTypeLabel = DynamicLabel.label("Wild type");
    private Properties mineotaurProperties = new Properties();
    private String totalMemory;
    private String cache;
    private Set<String> relKeySet;
    private boolean toPrecompute;


    public DatabaseGenerator(String prop) {
        this.prop = prop;
        init();
    }

    /**
     *  Method for processing of environment variables and creating and starting an empty database.
     */
    protected void init() {
        try {
            processProperties();
            startDB();
            Mineotaur.LOGGER.info("Database created.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Processing input properties and creating a directory to store configuration files.
     * @throws IOException if there is an error with the input property file
     */
    protected void processProperties() throws IOException {
        properties = new PropertyResourceBundle(new FileReader(prop));
        name = properties.getString("name");
        group = properties.getString("group");
        groupName = properties.getString("groupName");
        groupLabel = DynamicLabel.label(group);
        descriptive = properties.getString("descriptive");
        descriptiveLabel = DynamicLabel.label(descriptive);
        unique = Arrays.asList(properties.getString("unique").split(","));
        separator = properties.getString("separator");


        //mineotaurProperties.put("base_dir", name);
        totalMemory = properties.getString("total_memory");
        cache = properties.getString("cache");
        //path = new StringBuilder(name).append(FILE_SEPARATOR).append(CONF).append(FILE_SEPARATOR).toString();
        path = name + File.separator + CONF + File.separator;
        dbPath = new StringBuilder(name).append(FILE_SEPARATOR).append(DB).append(FILE_SEPARATOR).toString();
        createDirs();
        createRelationships(properties.getString("relationships"));
        relKeySet = relationships.keySet();
        toPrecompute = "true".equals(properties.getString("precompute"));
    }

    /**
     * Method for creating a directory for the configuration files.
     */
    protected void createDirs() {
        new File(name).mkdir();
        //System.out.println(new StringBuilder(name).append(FILE_SEPARATOR).append(CONF).toString());

        new File(path).mkdir();
    }

    /**
     * Creates relationships between the appropriate nodes.
     * @param rels A string containing the names. Each relationship should be separated by a ',', while the nodes should be separated by '-'.
     */
    protected void createRelationships(String rels) {
        String[] terms = rels.split(",");
        for (String s : terms) {
            String[] objects = s.split("-");
            Map<String, RelationshipType> map = relationships.get(objects[0]);
            if (map == null) {
                map = new HashMap<>();
                relationships.put(objects[0], map);
            }
            map.put(objects[1], DynamicRelationshipType.withName(new StringBuilder(objects[0]).append("_AND_").append(objects[1]).toString()));
        }
    }

    /**
     * Starts the database. If there was no database in the path present, a new instance is created.
     */
    protected void startDB() {
        GraphDatabaseBuilder gdb = new GraphDatabaseFactory().newEmbeddedDatabaseBuilder(dbPath);
        gdb.setConfig(GraphDatabaseSettings.all_stores_total_mapped_memory_size, totalMemory);
        gdb.setConfig(GraphDatabaseSettings.cache_type, cache);
        db = gdb.newGraphDatabase();
        registerShutdownHook(db);
        ggo = GlobalGraphOperations.at(db);
    }

    /**
     * Registers a shutdown hook for the database (from neo4j.com).
     * @param graphDb The database.
     */
    protected static void registerShutdownHook(final GraphDatabaseService graphDb) {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                graphDb.shutdown();
            }
        });
    }

    /**
     * Public method to generate the database from the inputs provided.
     * @param dataFile A ?SV file containing the data to be processed.
     * @param labelFile A ?SV file containg the label information for the group object.
     * @throws IllegalAccessException
     * @throws IOException
     * @throws InstantiationException
     * @throws CannotCompileException
     * @throws NotFoundException
     * @throws NoSuchFieldException
     */
    public void generateDatabase(String dataFile, String labelFile) throws IllegalAccessException, IOException, InstantiationException, CannotCompileException, NotFoundException, NoSuchFieldException {
        Mineotaur.LOGGER.info("Processing input data.");
        readInput(dataFile);
        Mineotaur.LOGGER.info("Processing label data.");
        labelGenes(labelFile);
        if (toPrecompute) {
            Mineotaur.LOGGER.info("Precomputing nodes.");
            precompute(Integer.valueOf(properties.getString("precompute_limit")));
        }
        else {
            mineotaurProperties.put("query_relationship", relationships.get(group).get(descriptive));
        }
        Mineotaur.LOGGER.info("Generating property files.");
        storeFeatureNames(db);
        storeGroupnames(db);
        generatePropertyFile();
        Mineotaur.LOGGER.info("Database generation finished. Start Mineotaur instance with -start " + name);
    }

    /**
     * Method for processing the input provided.
     * @param input Path to the input file.
     * @throws IOException
     * @throws javassist.NotFoundException
     * @throws CannotCompileException
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws NoSuchFieldException
     */
    protected void readInput(String input) throws IOException, javassist.NotFoundException, CannotCompileException, IllegalAccessException, InstantiationException, NoSuchFieldException {
        try (BufferedReader br = new BufferedReader(new FileReader(input))) {
            Mineotaur.LOGGER.info("Processing metadata.");
            header = br.readLine().split(separator);
            nodeTypes = br.readLine().split(separator);
            dataTypes = br.readLine().split(separator);
            processHeader();
            keySet = signatures.keySet();
            generateClasses();
            processData(br, Integer.valueOf(properties.getString("process_limit")));

        }
    }

    /**
     * Method for processing the header information provided in the input.
     */
    private void processHeader() {
        int lineSize = header.length;
        signatures = new HashMap<>();
        hits = new HashMap<>();
        numericData = new ArrayList<>();
        ids = new HashMap<>();

        for (int i = 0; i < lineSize; ++i) {
            if (nodeTypes[i].equals(HIT)) {
                if (!hits.containsKey(header[i])) {
                    hits.put(header[i], DynamicLabel.label(header[i]));
                }
                continue;
            }
            List<Integer> indices = signatures.get(nodeTypes[i]);
            if (indices == null) {
                indices = new ArrayList<>();
                signatures.put(nodeTypes[i], indices);
                labels.put(nodeTypes[i], DynamicLabel.label(nodeTypes[i]));
            }
            indices.add(i);
            if (NUMBER.equals(dataTypes[i])) {
                numericData.add(i);
            }
            if (FILTER.equals(dataTypes[i])) {
                filters.add(i);
            }
            if (ID.equals(dataTypes[i])) {
                List<String> idList = ids.get(nodeTypes[i]);
                if (idList == null) {
                    idList = new ArrayList<>();
                    ids.put(nodeTypes[i], idList);
                }
                idList.add(header[i]);

            }
        }
    }

    /**
     * Method for generating classes for the object types defined in the input file.
     * @throws NotFoundException
     * @throws CannotCompileException
     */
    protected void generateClasses() throws NotFoundException, CannotCompileException {
        ClassPool pool = ClassPool.getDefault();
        for (String key : keySet) {
            CtClass claz = pool.makeClass(key);
            List<Integer> indices = signatures.get(key);
            for (Integer i : indices) {
                CtClass type;
                if (dataTypes[i].equals(NUMBER)) {
                    type = CtClass.doubleType;
                } else {
                    type = pool.get(JAVA_LANG_STRING);
                }
                CtField field = new CtField(type, header[i], claz);
                field.setModifiers(Modifier.PUBLIC);
                claz.addField(field);
            }
            List<String> idFields = ids.get(key);
            if (idFields != null) {
                String methodBody = buildEquals(claz.getName(), idFields);
                CtMethod method = CtMethod.make(methodBody, claz);
                claz.addMethod(method);
            }
            classes.put(key, claz.toClass());
        }
    }

    /**
     * Method to symbolicly build equals method for a generated Java class.
     * @param name The name of the class.
     * @param idFields The names of the fields act as identifiers for the class.
     * @return The text of the equals method.
     */
    protected String buildEquals(String name, List<String> idFields) {
        StringBuilder sb = new StringBuilder();
        sb.append("public boolean equals(Object o) {\n");
        sb.append("if (this == o) return true;\n" +
                "        if (o == null || getClass() != o.getClass()) return false;\n" +
                name + " obj = (" + name + ") o;\n");
        for (String id : idFields) {
            sb.append("if (!this.").append(id).append(".equals(obj.").append(id).append(")) return false;\n");
        }
        sb.append("return true;\n");
        sb.append("        }");
        return sb.toString();
    }

    /**
     * Method to process the data provided in the input file line by line.
     * @param br the BufferedReader instance set to the first data line.
     * @throws IOException
     * @throws IllegalAccessException
     * @throws InstantiationException
     * @throws NoSuchFieldException
     */
    protected void processData(BufferedReader br, int limit) throws IOException, IllegalAccessException, InstantiationException, NoSuchFieldException {
        String line;
        Mineotaur.LOGGER.info("Processing data...");
        int lineCount = 0, nodeCount = 0;
        Transaction tx = null;
        try {
            tx = db.beginTx();
            while ((line = br.readLine()) != null) {
                System.out.println("Line #" + (lineCount++));
                String[] terms = line.split(separator);
                Map<String, Object> data = new HashMap<>();
                for (String key : keySet) {
                    List<Integer> indices = signatures.get(key);
                    Class claz = classes.get(key);
                    Object o = claz.newInstance();
                    for (Integer i : indices) {
                        if (!terms[i].isEmpty()) {
                            Field field = claz.getDeclaredField(header[i]);
                            if (numericData.contains(i)) {
                                field.setDouble(o, Double.valueOf(terms[i]));
                            } else {
                                field.set(o, terms[i]);
                            }
                        }
                    }
                    data.put(key, o);
                }
                Map<String, Node> newNodes = new HashMap<>();
                for (String key : keySet) {
                    nodeCount++;
                    if (unique.contains(key)) {
                        newNodes.put(key, createNode(data.get(key)));

                    } else {
                        newNodes.put(key, lookupNode(data.get(key)));
                    }
                }

                for (String key : relKeySet) {
                    Map<String, RelationshipType> rels = relationships.get(key);
                    nodeCount++;
                    Node n1 = newNodes.get(key);
                    Set<String> innerKeySet = rels.keySet();
                    for (String s : innerKeySet) {
                        Node n2 = newNodes.get(s);
                        if (n2 == null) {
                            System.out.println(s);
                        }
                        n1.createRelationshipTo(n2, rels.get(s));
                    }
                }
                if (nodeCount > limit) {
                    nodeCount = 0;
                    tx.success();
                    tx.close();
                    tx = db.beginTx();
                }
            }
        }
        finally {
            if (tx != null) {
                tx.success();
                tx.close();
            }
        }

        Mineotaur.LOGGER.info(lineCount + " lines processed.");
    }

    /**
     * Method to create node using reflection from the generated object.
     * @param o The Java object containing all data extracted from the input.
     * @return The Node created from the object.
     * @throws IllegalAccessException
     */
    protected Node createNode(Object o) throws IllegalAccessException {
        Class claz = o.getClass();
        Label label = labels.get(claz.getName());
        Node node = db.createNode(label);
        Field[] fields = claz.getDeclaredFields();
        for (Field f : fields) {
            node.setProperty(f.getName(), f.get(o));
        }
        return node;
    }

    /**
     * Looks up whether a Node with the same identifiers already created.
     * @param o The Java object containing all data extracted from the input.
     * @return The retireved Node object or if not exists, a new one created by createNode.
     * @throws IllegalAccessException
     */
    protected Node lookupNode(Object o) throws IllegalAccessException {
        Class claz = o.getClass();
        String label = claz.getName();
        List<Node> storedNodes = nonUniqueNodes.get(label);
        if (storedNodes == null) {
            storedNodes = new ArrayList<>();
            nonUniqueNodes.put(label, storedNodes);
        }
        for (Node node : storedNodes) {
            boolean same = true;
            Field[] fields = claz.getDeclaredFields();
            for (Field f : fields) {
                String key = f.getName();
                Object nodeValue = node.getProperty(key, null);
                Object storedValue = f.get(o);
                if (!storedValue.equals(nodeValue)) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return node;
            }
        }
        Node node = createNode(o);
        storedNodes.add(node);
        return node;
    }

    /**
     * Method to label group objects.
     * @param file The ?SV file containing the labels.
     */
    protected void labelGenes(String file) {
        try (Transaction tx = db.beginTx(); BufferedReader br = new BufferedReader(new FileReader(file))) {
            String[] header = br.readLine().split(separator);
            //System.out.println(Arrays.toString(header));
            String line;
            //int count = 0;
            List<String> list = new ArrayList<>();
            list.addAll(Arrays.asList(header).subList(1, header.length));
            FileUtil.saveList(new StringBuilder(path).append("mineotaur.hitLabels").toString(), list);
            while ((line = br.readLine()) != null) {
                String[] terms = line.split(separator);
                Iterator<Node> nodes = db.findNodesByLabelAndProperty(groupLabel, header[0], terms[0]).iterator();
                if (!nodes.hasNext()) {
                    throw new IllegalArgumentException("No such gene.");
                }
                Node node = nodes.next();
                if (nodes.hasNext()) {
                    throw new IllegalArgumentException("Id is not unique.");
                }
                boolean hasLabel = false;
                for (int i = 1; i < terms.length; ++i) {
                    if (terms[i].equals("1")) {
                        //count++;
                        node.addLabel(DynamicLabel.label(header[i]));
                        hasLabel = true;
                    }
                }
                if (!hasLabel) {
                    node.addLabel(wildTypeLabel);
                }
                tx.success();
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * Method to create precomputated Nodes.
     * @param limit The maximum number of fetched Nodes per transactions.
     * @throws IOException
     */
    protected void precompute(int limit) throws IOException {
        int count = 0;
        Transaction tx = null;
        try {
            tx = db.beginTx();
            Label precomputed = DynamicLabel.label(group + "_" + COLLECTED);
            RelationshipType preRT = DynamicRelationshipType.withName(COLLECTED);
            RelationshipType rt = relationships.get(group).get(descriptive);
            Iterator<Node> nodes = ggo.getAllNodesWithLabel(groupLabel).iterator();
            while (nodes.hasNext()) {
                count++;
                Node node = nodes.next();
                Iterator<Relationship> rels = node.getRelationships(rt).iterator();
                Map<String, List<Double>> features = new HashMap<>();
                if (node.hasRelationship(preRT)) {
                    continue;
                }
                while (rels.hasNext()) {
                    Relationship rel = rels.next();
                    Node other = rel.getOtherNode(node);
                    if (!other.hasLabel(descriptiveLabel)) {
                        continue;
                    }
                    Iterator<String> properties = other.getPropertyKeys().iterator();
                    while (properties.hasNext()) {
                        String key = properties.next();
                        Object o = other.getProperty(key);
                        if (o instanceof Number) {
                            List<Double> values = features.get(key);
                            if (values == null) {
                                values = new ArrayList<>();
                                features.put(key, values);
                            }
                            values.add((Double) o);
                        }
                    }
                    Node pre = db.createNode(precomputed);
                    pre.createRelationshipTo(node, preRT);
                    Set<String> keySet = features.keySet();
                    for (String s : keySet) {
                        List<Double> values = features.get(s);
                        int size = values.size();
                        double[] arr = new double[size];
                        for (int i = 0; i < size; ++i) {
                            arr[i] = values.get(i);
                        }
                        pre.setProperty(s, arr);
                    }
                    count++;
                    if (count % limit == 0) {
                        tx.success();
                        tx.close();
                        tx = db.beginTx();
                    }
                }
            }
        } finally {
            if (tx != null) {
                tx.success();
                tx.close();
            }

        }
    }

    /**
     * Method to store feature names in an external file.
     * @param db The GraphDatabaseService instance.
     */
    protected void storeFeatureNames(GraphDatabaseService db) {
        try (Transaction tx = db.beginTx()) {
            GlobalGraphOperations ggo = GlobalGraphOperations.at(db);
            Iterator<Node> iterator = ggo.getAllNodesWithLabel(descriptiveLabel).iterator();
            List<String> labels = new ArrayList<>();
            while (iterator.hasNext()) {
                Node node = iterator.next();
                Iterator<String> props = node.getPropertyKeys().iterator();
                while (props.hasNext()) {
                    String prop = props.next();
                    if (!labels.contains(prop)) {
                        labels.add(prop);
                    }
                }
            }
            System.out.println(labels.toString());
            FileUtil.saveList(new StringBuilder(path).append("mineotaur.features").toString(), labels);
            tx.success();
        }
    }

    /**
     * Method to store the names of the group objects in an external file.
     * @param db The GraphDatabaseService instance.
     */
    protected void storeGroupnames(GraphDatabaseService db) {
        try (Transaction tx = db.beginTx()) {
            GlobalGraphOperations ggo = GlobalGraphOperations.at(db);
            Iterator<Node> iterator = ggo.getAllNodesWithLabel(groupLabel).iterator();
            List<String> names = new ArrayList<>();
            while (iterator.hasNext()) {
                Node node = iterator.next();
                String gname = (String) node.getProperty(groupName, null);
                if (gname != null) {
                    names.add(gname);
                }

                //labels.add(node.getProperty("GFP","") + ", " + node.getProperty("Deletion",""));
            }
            //System.out.println(labels.toString());
            FileUtil.saveList(new StringBuilder(path).append("mineotaur.groupNames").toString(), names);
            tx.success();
        }
    }

    /**
     * Method to store the filters in an external file.
     */
    protected void storeFilters() {
        List<String> filterNames = new ArrayList<>();
        /*for (Integer i : filters) {
            filterNames.add(header[i]);
        }*/
        filterNames.add("GrowthStage");
        try (Transaction tx = db.beginTx()) {
            GlobalGraphOperations ggo = GlobalGraphOperations.at(db);
            Iterator<Node> iterator = ggo.getAllNodesWithLabel(descriptiveLabel).iterator();
            List<String> filterValues = new ArrayList<>();
            while (iterator.hasNext()) {
                Node node = iterator.next();
                Iterator<String> props = node.getPropertyKeys().iterator();
                while (props.hasNext()) {
                    String prop = props.next();
                    if (filterNames.contains(prop)) {
                        String value = (String) node.getProperty(prop);
                        if (!filterValues.contains(value)) {
                            filterValues.add((String) node.getProperty(prop));
                        }

                    }
                }
            }
            FileUtil.saveList(new StringBuilder(path).append("mineotaur.filters").toString(), filterValues);
            tx.success();
        }
        //FileUtil.saveList(new StringBuilder(path).append("mineotaur.filters").toString(), filterNames);
    }

    /**
     * Method to store the environment properties for the Mineotaur instance.
     * @throws IOException
     */
    protected void generatePropertyFile() throws IOException {
        if (filters == null || filters.isEmpty()) {
            mineotaurProperties.put("hasFilters", "false");
        }
        else {
            mineotaurProperties.put("hasFilters", "true");
            mineotaurProperties.put("filterName", filters.get(0));
            storeFilters();
        }
        if (toPrecompute) {
            mineotaurProperties.put("query_relationship", "COLLECTED");
        }
        else {
            mineotaurProperties.put("query_relationship", relationships.get(groupLabel).get(descriptiveLabel));
        }
        mineotaurProperties.put("group", group);
        mineotaurProperties.put("groupName", groupName);
        mineotaurProperties.put("total_memory", totalMemory);
        mineotaurProperties.put("db_path", dbPath);
        mineotaurProperties.put("cache", "soft");
        mineotaurProperties.store(new FileWriter(new StringBuilder(path).append("mineotaur.properties").toString()), "Mineotaur configuration properties");
    }

    public static void main(String[] args) {
        DatabaseGenerator databaseGenerator = new DatabaseGenerator("input/merovingian.input");
        /*databaseGenerator.descriptiveLabel = DynamicLabel.label("CELL");
        databaseGenerator.storeFeatureNames(databaseGenerator.db);
        databaseGenerator.storeFilters();*/
        try {
            databaseGenerator.generatePropertyFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }




}