import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

public class MobySimulator {

    private static final String DATA_FILE_PREFIX = "data/";
    private static final String CONFIG_FILE_PREFIX = "data/seeds/";
    private static final String RESULT_FILE_PREFIX = "data/results/";
    private static final String DATA_FILE_FORMAT = ".twr";
    private static final String CONFIG_FILE_FORMAT = ".config";
    private static final String RESULT_FILE_FORMAT = ".csv";
    private static final String QUEUE_OCCUPANCY_FORMAT = ".qo";
    private static final String MESSAGE_DELAYS_FORMAT = ".md";


    private static HashMap<Integer, HashMap<Integer, Double> > messageQueue = new HashMap<>();
    private static HashMap<Integer, Set<Integer>> networkStateNew = null;
    private static HashMap<Integer, BitSet> messageQueueBits = new HashMap<>();

    public static void main(String[] args){



        // Create all datastructures needed for simulation.
        String configID = "";
        String configFile = "";
        String resultFile = "";
        String queueOccupancyFile = "";
        String messageDelaysFile = "";
        JsonObject configurationJson = null;
        JsonParser jsonParser = new JsonParser();
        int city;
        int startDay;
        int endDay;
        int seed;
        int queueSize;
        int numberOfDays;
        int threshold;
        int dosNumber;
        int jamTower;
        int jamTowerLogic;
        int jamUser;
        int jamUserLogic;
        int allUserLength;
        int userPoolLength;

        List<Integer> userPool = new ArrayList<>();
        List<Integer> jamTowerList = new ArrayList<>();
        List<Integer> jamUserList = new ArrayList<>();
        List<MobyMessage> messageList = new ArrayList<>();
        List<List<Integer>> deleteUsersList = new ArrayList<>();
        String distributionType;
        String slackHook = "";

        JsonArray jsonArray;
        JsonObject jsonObject;
        int i;
        int j;
        int currentDay;
        int currentHour;
        ArrayList<Integer> integerArrayList;
        HashMap<Integer, Set<Integer>> networkStateOld = null;
        String currentDataFile;
        boolean firstHour;
        Scanner scanner = null;
        String line;
        String[] parts;
        int hour;
        int towerID;
        String userIDs;
        Set<Integer> userSet;
        Set<Integer> currentHourUsers;
        int userCounter;
        int simulationHour;
        int messagesInCirculation = 0;
        HashMap<Integer, Integer> messageDelays = new HashMap<>();
        BufferedWriter resultsFileBuffer = null;
        BufferedWriter queueOccupancyFileBuffer = null;

        try {
            configID = args[0];
        } catch(ArrayIndexOutOfBoundsException e) {
            System.out.println("Please pass a configuration ID!!");
        }

        // Build all input output filenames.
        configFile = CONFIG_FILE_PREFIX + configID + CONFIG_FILE_FORMAT;
        resultFile = RESULT_FILE_PREFIX + configID + RESULT_FILE_FORMAT;
        queueOccupancyFile = RESULT_FILE_PREFIX + configID + QUEUE_OCCUPANCY_FORMAT;
        messageDelaysFile = RESULT_FILE_PREFIX + configID + MESSAGE_DELAYS_FORMAT;
        System.out.println("Configuration file: " + configFile);


        // Get config JSON from file.
        try {
            configurationJson = jsonParser.parse(new FileReader(configFile)).getAsJsonObject();
        } catch (FileNotFoundException e) {
            System.out.println("Invalid config file supplied!!");
        }


        // Read in all required configurations
        city = configurationJson.get("city").getAsInt();
        startDay = configurationJson.get("start-day").getAsInt();
        endDay = configurationJson.get("end-day").getAsInt();
        seed = configurationJson.get("seed").getAsInt();
        queueSize = configurationJson.get("queuesize").getAsInt();
        threshold = configurationJson.get("threshold").getAsInt();
        dosNumber = configurationJson.get("dos-number").getAsInt();
        jamTower = configurationJson.get("jam-tower").getAsInt();
        jamTowerLogic = configurationJson.get("jam-tower-logic").getAsInt();
        jamUser = configurationJson.get("jam-user").getAsInt();
        jamUserLogic = configurationJson.get("jam-user-logic").getAsInt();
        numberOfDays = endDay - startDay;
        slackHook = configurationJson.get("slack-hook").getAsString();

        // Convert messages to our own objects.

        jsonArray = configurationJson.get("messages").getAsJsonArray();
        for(i = 0; i < jsonArray.size(); i++) {
            jsonObject = (JsonObject) jsonArray.get(i);
            messageList.add(new MobyMessage(
                    jsonObject.get("id").getAsInt(),
                    jsonObject.get("ttl").getAsInt(),
                    jsonObject.get("src").getAsInt(),
                    jsonObject.get("dst").getAsInt(),
                    jsonObject.get("hour").getAsInt(),
                    jsonObject.get("trust").getAsDouble()
            ));
        }

        jsonArray = configurationJson.get("userpool").getAsJsonArray();
        for(i = 0; i < jsonArray.size(); i++) {
            userPool.add(jsonArray.get(i).getAsInt());
        }

        jsonArray = configurationJson.get("jam-user-list").getAsJsonArray();
        for(i = 0; i < jsonArray.size(); i++) {
            jamUserList.add(jsonArray.get(i).getAsInt());
        }

        jsonArray = configurationJson.get("jam-tower-list").getAsJsonArray();
        for(i = 0; i < jsonArray.size(); i++) {
            jamTowerList.add(jsonArray.get(i).getAsInt());
        }

        jsonObject = configurationJson.get("del-users").getAsJsonObject();
        for(i = 0; i < jsonObject.size(); i++) {
            jsonArray = jsonObject.get("" + i).getAsJsonArray();
            integerArrayList = new ArrayList<>();
            for(j = 0; j < jsonArray.size(); j++) {
                integerArrayList.add(jsonArray.get(j).getAsInt());
            }
            deleteUsersList.add(integerArrayList);
        }

        // Done with the configuration json!
        configurationJson = null;
        firstHour = true;
        System.out.println("Creating per user hashmaps");
        for(int user : userPool) {
            messageQueue.put(user, new HashMap<>());
            messageQueueBits.put(user, new BitSet(messageList.size()));
        }
        System.out.println(messageQueue.size());

        // Parse towers file and get all information.
        networkStateOld = new HashMap<>();

        // Open results file and queue occupancy file.
        try {
            resultsFileBuffer = new BufferedWriter(new FileWriter(resultFile));
            queueOccupancyFileBuffer = new BufferedWriter(new FileWriter(queueOccupancyFile));
        } catch (IOException e) {
            System.out.println("Problem opening results file or queue occupancy file!!");
        }
        // For the range of days.
        for(currentDay = startDay; currentDay < endDay; currentDay ++) {
            // For the hours of a day.
            for(currentHour = 0; currentHour < 24; currentHour++) {
                networkStateNew = new HashMap<>();
                currentHourUsers = new HashSet<>();
                simulationHour = currentHour + (24 * (currentDay - startDay));

                if(threshold==0)
                    currentDataFile = DATA_FILE_PREFIX + city + "/" + currentDay + "_" + currentHour + DATA_FILE_FORMAT;
                else
                    currentDataFile = DATA_FILE_PREFIX + city + "_" + threshold + "/" + startDay + "/" + numberOfDays +
                            "/" + currentDay + "_" + currentHour + DATA_FILE_FORMAT;

                if(!firstHour){
                    System.out.println("Message delivery count: " + messageDelays.size() + " of: " + messagesInCirculation);
                    System.out.println("Delivery rate: " + (double) messageDelays.size() / messagesInCirculation);
                } else
                    firstHour = false;

                System.out.println("Processing hour: " + currentHour + " File: " + currentDataFile);
                try {
                    scanner = new Scanner(new File(currentDataFile));
                } catch(FileNotFoundException e) {
                    System.out.println(e.toString());
                }

                userCounter = 0;

                while(scanner.hasNext()) {
                    line = scanner.nextLine();
                    parts = line.split(",");
                    hour = Integer.parseInt(parts[0]);
                    towerID = Integer.parseInt(parts[1]);
                    userIDs = parts[2];
                    userSet = new HashSet<>();

                    for (String user : userIDs.split("\\|")) {
                        userSet.add(Integer.parseInt(user));
                    }

                    userCounter += userSet.size();
                    networkStateNew.put(towerID, userSet);
                    currentHourUsers.addAll(userSet);
                }
                scanner.close();

                System.out.println("Total users seen this hour:" + userCounter);
                System.out.println("Unique users seen this hour:" + currentHourUsers.size());

                // Send out messages for this hour.
                // TODO: Might be a better way to do this than parse the entire list of messages.
                for(MobyMessage m : messageList) {
                    if(m.hour == simulationHour) {
                        messageQueue.get(m.src).put(m.id, 1.0);
                        messageQueueBits.get(m.src).set(m.id);
                        messagesInCirculation += 1;
                    }
                }


                // At this point, the old simulator would figure out who moved and things like that, that's unnecessary imo.
                List<Integer> sortedList = new ArrayList<>();
                sortedList.addAll(networkStateNew.keySet());
                Collections.sort(sortedList);

                // Simulation message exchanges.
                messageExchangeHandler(sortedList, currentDay, currentHour, dosNumber, queueSize);

                // Check message deliveries.
                for(MobyMessage m : messageList) {
                    if(!m.getDelivered()) {
                        try {
                            // TODO: This could be moved to checking the bitset instead.
                            if (messageQueue.get(m.dst).containsKey(m.id)) {
                                m.setDelivered();
                                messageDelays.put(m.id, simulationHour - m.hour);
                                System.out.println("Message: " + m.id + " Delay: " + (simulationHour - m.hour));
                            }
                        } catch (NullPointerException e) {
                            // Dead user!!
                        }
                    }
                }

                // Write delivery ratio.
                try {
                    resultsFileBuffer.write(
                                currentDay + "," +
                                    currentHour + "," +
                                    currentHourUsers.size() + "," +
                                    messageDelays.size() + "," +
                                    messagesInCirculation + '\n');
                } catch (IOException e) {
                    System.out.println("Problem writing delivery ratios!!");
                }

                // Write queue occupancy.
                try {
                    for(int user : currentHourUsers) {
                        queueOccupancyFileBuffer.write(
                                currentDay + "," +
                                        currentHour + "," +
                                        user + "," +
                                        messageQueueBits.get(user).cardinality() + '\n');

                    }
                } catch (IOException e) {
                    System.out.println("Problem writing queue occupancies!!");
                }

                // Delete dead users.
                System.out.println("Deleting " + deleteUsersList.get(simulationHour).size() + " users!!");
                for(int user : deleteUsersList.get(simulationHour)) {
                    messageQueue.remove(user);
                    messageQueueBits.remove(user);
                }
                // TODO: Test to see what happens if we manually trigger GC after these deletes.

                networkStateOld = networkStateNew;
                // Do next hour.
            }
        }

        // Close results file and queue occupancy file.
        try {
            resultsFileBuffer.close();
            queueOccupancyFileBuffer.close();
        } catch (IOException e) {
            System.out.println("Problem closing results file or qo file!!");
        }

        // Write message delays.
        try {
            BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(messageDelaysFile));
            Iterator iterator = messageDelays.entrySet().iterator();
            while(iterator.hasNext()) {
                HashMap.Entry entry = (HashMap.Entry)iterator.next();
                bufferedWriter.write(entry.getKey().toString() + "," + entry.getValue().toString() + '\n');
            }
            bufferedWriter.close();
        } catch(IOException e) {
            System.out.println("IOException at writing message delays file!!");
        }

        // Simulation done, send message to slack.
        if(!slackHook.isEmpty()) {
            System.out.println("Sending slack message!!");
            String payload = null;
            HttpPost httpPost = new HttpPost(slackHook);
            httpPost.setHeader("Content-type", "application/json");
            CloseableHttpClient httpClient = HttpClients.createDefault();
            try {
                payload = "{ \"text\": \"Simulation " + configID + " done on " + InetAddress.getLocalHost().getHostName() + " \" }";
                httpPost.setEntity(new StringEntity(payload));
                CloseableHttpResponse closeableHttpResponse  = httpClient.execute(httpPost);
                if(closeableHttpResponse.getStatusLine().getStatusCode() == 200)
                    System.out.println("Successful post to slack!!");
                else
                    System.out.println("Failed trying to post to slack!!");
                closeableHttpResponse.close();
            } catch (UnknownHostException e) {
                System.out.println("Unknown host!!");
            } catch (UnsupportedEncodingException e) {
                System.out.println("Unsupported encoding!!");
            } catch (IOException e) {
                System.out.println("IOException!!");
            }
        }

    }

    //Message exchange handler
    private static void messageExchangeHandler(List<Integer> towerIDs, int currentDay, int currentHour,
                                        int dosNumber, int queueSize){
        // TODO: Perform DoS exchanges.

        // Perform exchanges one way.
        for(int tower : towerIDs)
            performMessageExchange(networkStateNew.get(tower), currentDay, currentHour);

        Collections.reverse(towerIDs);

        for(int tower : towerIDs)
            performMessageExchange(networkStateNew.get(tower), currentDay, currentHour);

        // Perform exchanges the other way.
    }

    // Perform message exchange in tower
    private static void performMessageExchange(Set<Integer> users, int currentDay, int currentHour){
        // For all pairs of users, just send messages from one queue to the other.
        String queueKey = currentDay + "," + currentHour;
        HashMap<Integer, Double> mq1, mq2;
        BitSet mqb1, mqb2, u1mqb, u2mqb;
        for (int u1 : users) {
            for (int u2 : users) {
                mq1 = messageQueue.get(u1);
                mq2 = messageQueue.get(u2);

                u1mqb = messageQueueBits.get(u1);
                u2mqb = messageQueueBits.get(u2);

                mqb1 = (BitSet) messageQueueBits.get(u1).clone();
                mqb2 = (BitSet) messageQueueBits.get(u2).clone();

                mqb1.or(messageQueueBits.get(u2));
                mqb1.andNot(messageQueueBits.get(u1));

                mqb2.or(messageQueueBits.get(u1));
                mqb2.andNot(messageQueueBits.get(u2));

                for(int bitIndex = mqb1.nextSetBit(0); bitIndex >= 0; bitIndex = mqb1.nextSetBit(bitIndex+1)) {
                    mq1.put(bitIndex, mq2.get(bitIndex));
                    u1mqb.set(bitIndex);
                }

                for(int bitIndex = mqb2.nextSetBit(0); bitIndex >= 0; bitIndex = mqb2.nextSetBit(bitIndex+1)) {
                    mq2.put(bitIndex, mq1.get(bitIndex));
                    u2mqb.set(bitIndex);
                }
            }
        }

    }
}

class MobyMessage {
    int id;
    int ttl;
    int src;
    int dst;
    int hour;
    double trust;
    private boolean delivered;

    public MobyMessage(int identifier, int timeToLive, int source, int destination,
                       int hourSent, double trustScore) {
        this.id = identifier;
        this.ttl = timeToLive;
        this.src = source;
        this.dst = destination;
        this.hour = hourSent;
        this.trust = trustScore;
        this.delivered = false;
    }

    public void setDelivered() {
        this.delivered = true;
    }

    public boolean getDelivered() {
        return this.delivered;
    }
    // Use this to store delivered information?
}
