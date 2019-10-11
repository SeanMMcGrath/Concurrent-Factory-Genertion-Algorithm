
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;


import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.concurrent.*;

public class Controller {

    @FXML
    Label fitnessLabel;

    @FXML
    Label currentGenLabel;

    @FXML
    GridPane FactoryGrid;

    private ArrayList<ShapeFactory> backlog = new ArrayList<>();
    private CountDownLatch startingLine;
    private ShapeFactory[] factories;
    private BlockingQueue<String> unprocessed;

    private BlockingQueue<Generation> genQueue = new ArrayBlockingQueue<>(10000);//10 is arbitrary

    int m = 49;//max number of spaces for shapes to be placed, try to keep as a root so that a square gui can be made neatly
    int n = 32;//The number of shapes
    int k = 4;//The number of members of the population. When run on 32+ core server, k must be at least 32 for project

    //images for the display
    private Image zero = new Image(getClass().getResourceAsStream("0.png"));
    private Image one = new Image(getClass().getResourceAsStream("1.png"));
    private Image two = new Image(getClass().getResourceAsStream("2.png"));
    private Image three = new Image(getClass().getResourceAsStream("3.png"));
    private Image four = new Image(getClass().getResourceAsStream("4.png"));

    //the final generation for last display
    private final Generation[] fin = {null};

    public void startAlgorithm(ActionEvent e) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        int MAX = k * 100000;


        Runnable algorithm = () -> {
            Generation currentGeneration = null;

            DecimalFormat df2 = new DecimalFormat(".#####");

            //make sure there are enough processes to run the program smoothly
            int threads = Runtime.getRuntime().availableProcessors();
            if (threads < k) {
                System.out.println(threads + " processes available");
                System.out.println("Ending program");
                return;
            }

            initializeFactories();

            for (int i = 0; i < MAX; i++) {
                String temp = null;
                try {
                    temp = unprocessed.poll(5, TimeUnit.SECONDS);//wait for factories to be ready to communicate
                } catch (InterruptedException e12) {
                    e12.printStackTrace();
                }

                if (temp != null) {
                    //if something was retrieved process it
                    currentGeneration = process(temp, currentGeneration);
                } else {
                    //queue empty, dump info for debugging and try again
                    System.out.println(unprocessed.isEmpty());
                    for (int x = 0; x < k; x++) {
                        System.out.println(factories[x].getName() + " is " + factories[x].getState());
                        System.out.println(df2.format(factories[x].getFitness()));
                        System.out.println("Generation #" + factories[x].getGenNum());
                    }
                }

                //if the current generation is done, display it
                if (currentGeneration != null && currentGeneration.isMax()) {
                    //display
                    System.out.println("Generation #" + currentGeneration.getGenNum());
                    System.out.println("Best fit: " + currentGeneration.getBest().getFitness());


                    try {//put in queue for displayer to take
                        genQueue.put(currentGeneration);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }


                    //start a new generation and handle and backlog factories
                    currentGeneration = new Generation(currentGeneration.getGenNum() + 1, k);
                    if (!backlog.isEmpty()) {//work on backlog
                        currentGeneration = handleBacklog(currentGeneration);
                    }
                }
            }
            //end running threads
            closeOut(currentGeneration);
        };

        //displays generations every .5 seconds, as well as last one when program done
        Runnable displayer = () -> {
            boolean doRun = true;
            Runnable finalUpdate = () -> {
                if (fin[0] != null) {
                    System.out.println("final display");
                    setDisplay(fin[0]);
                }
            };
            Runnable updater = () -> {
                try {
                    final Generation gen = genQueue.take();
                    genQueue.clear();
                    setDisplay(gen);
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            };
            do {
                try {
                    Thread.sleep(500);//update every .5s

                    Platform.runLater(updater);
                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                }

                if (fin[0] != null) {
                    doRun = false;
                    Platform.runLater(finalUpdate);
                }

            } while (doRun);
        };

        executor.execute(displayer);
        executor.execute(algorithm);


    }

    /**
     * starts that factories and lets them go with a count down latch
     */
    private void initializeFactories() {

        factories = new ShapeFactory[k];
        startingLine = new CountDownLatch(1);
        unprocessed = new ArrayBlockingQueue<>(k);

        for (int i = 0; i < k; i++) {//init factories
            factories[i] = new ShapeFactory(n, m, startingLine, unprocessed);
            factories[i].setName("F" + i);
            factories[i].start();
        }
        startingLine.countDown();

    }

    /**
     * processes a thread
     *
     * Flow: looks for factory that's ready to process in factory array -> waits for factory to be waiting -> if first gen, generate it and return
     * else check whether it is in current generation -> if already in current generation put in backlog to process later and return
     * else conduct crossover on it
     *
     * @param threadName        - name of thread to process
     * @param currentGeneration - current generation to possibly insert thread into
     * @return a modified current generation
     */
    private Generation process(String threadName, Generation currentGeneration) {
        for (int j = 0; j < k; j++) {
            if (factories[j].getName().equals(threadName)) {
                while (!factories[j].getState().equals(Thread.State.WAITING)) {
                    //wait for thread to be waiting
                }
                if (currentGeneration == null) {//if first gen
                    currentGeneration = new Generation(1, k);
                    ShapeFactory foo = factories[j].getSnapshot();
                    foo.setName(factories[j].getName());
                    currentGeneration.insert(foo);//only one so don't notify since not paired
                    return currentGeneration;
                } else {
                    for (ShapeFactory sf : currentGeneration.getFactories()) {
                        if (sf.getName().equals(factories[j].getName())) {//Wrong gen!
                            //send to backlog to be used after current gen finished
                            ShapeFactory bar = factories[j].getSnapshot();
                            bar.setName(factories[j].getName());
                            backlog.add(bar);
                            return currentGeneration;
                        }
                    }

                    //if thread is not a backlog thread,
                    return crossover(currentGeneration, j);
                }
            }
        }
        System.out.println("PROCESSING ERROR: thread " + threadName + "not found");
        return null;//shouldn't happen, indicates that there is no
    }

    /**
     * @param currentGeneration - reference to th current generation
     * @param factoryIndex      - which factory to decide whether to crossover
     * @return - returns modified generation
     */
    private Generation crossover(Generation currentGeneration, int factoryIndex) {
        ShapeFactory tempF = factories[factoryIndex].getSnapshot();
        tempF.setName(factories[factoryIndex].getName());
        currentGeneration.insert(tempF);
        if (currentGeneration.readyToProcess()) {
            ArrayList<ShapeFactory> toProcess = currentGeneration.getUnprocessed();
            if (toProcess.size() > 2) {
                System.out.println("toProcess size off, it is:" + toProcess.size());
            }
            int chance = 1;
            if (ThreadLocalRandom.current().nextInt(0, 5) == chance) {//20% chance that crossover occurs
                Exchanger<Integer> crossover = new Exchanger<>();
                int row = ThreadLocalRandom.current().nextInt(0, 7);
                int column = ThreadLocalRandom.current().nextInt(0, 7);
                for (int l = 0; l < k; l++) {
                    for (ShapeFactory factory : toProcess) {
                        if (factories[l].getName().equals(factory.getName())) {
                            factories[l].readyCrossover(crossover, row, column);
                            factories[l].free();
                        }
                    }
                }
            } else {//not doing crossover so free the two factories
                for (int l = 0; l < k; l++) {
                    for (int o = 0; o < toProcess.size(); o++) {
                        if (factories[l].getName().equals(toProcess.get(o).getName())) {
                            factories[l].free();
                        }
                    }
                }

            }
            currentGeneration.process(toProcess.get(0).getName(), toProcess.get(1).getName());
        } //else not ready to process so wait a bit
        return currentGeneration;
    }

    /**
     * puts all backlog generations into freshly made current generation
     *
     * @param currentGen - current generation
     * @return - a modified generation to replace current gen
     */
    private Generation handleBacklog(Generation currentGen) {
        Generation gen = currentGen;
        for (int j = 0; j < backlog.size(); j++) {
            ShapeFactory tempSF = backlog.get(j);
            ShapeFactory t = tempSF.getSnapshot();
            t.setName(tempSF.getName());
            gen.insert(t);
            if (gen.readyToProcess()) {
                ArrayList<ShapeFactory> toProcess = gen.getUnprocessed();
                if (toProcess.size() > 2) {
                }
                int chance = 1;
                if (ThreadLocalRandom.current().nextInt(0, 5) == chance) {//20% chance that crossover occurs
                    Exchanger<Integer> crossover = new Exchanger<>();
                    int row = ThreadLocalRandom.current().nextInt(0, 7);
                    int column = ThreadLocalRandom.current().nextInt(0, 7);
                    for (int v = 0; v < k; v++) {
                        for (ShapeFactory factory : toProcess) {
                            if (factories[v].getName().equals(factory.getName())) {
                                factories[v].readyCrossover(crossover, row, column);
                                factories[v].free();
                            }
                        }
                    }
                } else {//not doing crossover so free the two factories
                    for (int p = 0; p < k; p++) {
                        for (int o = 0; o < toProcess.size(); o++) {
                            if (factories[p].getName().equals(toProcess.get(o).getName())) {
                                factories[p].free();
                            }
                        }
                    }
                }
                gen.process(toProcess.get(0).getName(), toProcess.get(1).getName());
            }
        }
        backlog.clear();
        return gen;
    }

    /**
     * sets GUI with given information
     *
     * @param gen - generation to show in display
     */
    private void setDisplay(Generation gen) {
        currentGenLabel.setText(String.valueOf(gen.getGenNum()));
        fitnessLabel.setText(String.valueOf(gen.getBest().getFitness()));
        final int[][] layout = gen.getBest().getLayout();
        FactoryGrid.getChildren().clear();
        for (int i = 0; i < layout.length; i++) {
            for (int j = 0; j < layout[0].length; j++) {
                ImageView iv;
                if (layout[i][j] == 0) {
                    iv = new ImageView(zero);
                } else if (layout[i][j] == 1) {
                    iv = new ImageView(one);
                } else if (layout[i][j] == 2) {
                    iv = new ImageView(two);
                } else if (layout[i][j] == 3) {
                    iv = new ImageView(three);
                } else if (layout[i][j] == 4) {
                    iv = new ImageView(four);//empty spot
                } else {
                    iv = new ImageView();//shouldnt happen
                }
                iv.setFitHeight(50);
                iv.setFitWidth(50);
                FactoryGrid.add(iv, i, j);
            }
        }
    }

    private void closeOut(Generation gen) {
        //save last gen for display
        fin[0] = gen;
        //close out threads
        unprocessed.clear();
        for (int i = 0; i < k; i++) {
            factories[i].stopRunning();
            factories[i].free();
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e12) {
            e12.printStackTrace();
        }
        for (int i = 0; i < k; i++) {
            if (factories[i].getState().equals(Thread.State.WAITING) || factories[i].getState().equals(Thread.State.RUNNABLE)) {
                factories[i].interrupt();
            }
        }
    }
}

/**
 * Used to keep track of a record of past and current generations of ShapeFactories
 */
class Generation {
    private int genNum;
    private int k;
    private ArrayList<ShapeFactory> factories;
    private ArrayList<ShapeFactory> unprocessed;
    private ShapeFactory best;//current best of this gen

    public Generation(int gen, int max) {
        k = max;
        genNum = gen;
        factories = new ArrayList<>();
        unprocessed = new ArrayList<>();
        best = null;
    }

    public void insert(ShapeFactory sf) {
        if (best == null) {//if no best(first input), set best
            best = sf;
        } else if (best.getFitness() < sf.getFitness()) {//if input is better than current best, make it new best
            best = sf;
        }
        factories.add(sf);
        unprocessed.add(sf);
    }

    public boolean readyToProcess() {
        return unprocessed.size() == 2;
    }

    //factories have been processed, so remove them
    public void process(String fName1, String fName2) {
        for (int i = 0; i < unprocessed.size(); i++) {
            if (fName1.equals(unprocessed.get(i).getName())) {
                unprocessed.remove(i);
                break;
            }
        }
        for (int i = 0; i < unprocessed.size(); i++) {
            if (fName2.equals(unprocessed.get(i).getName())) {
                unprocessed.remove(i);
                break;
            }
        }
    }

    public ArrayList<ShapeFactory> getUnprocessed() {
        return unprocessed;
    }

    public ShapeFactory getBest() {//should only be used on finished generations
        return best;
    }

    public ArrayList<ShapeFactory> getFactories() {
        return factories;
    }

    public int getGenNum() {
        return genNum;
    }

    public boolean isMax() {
        if (factories.size() == k) {
            return true;
        } else if (factories.size() > k) {
            System.out.println("ERROR: Generation " + genNum + " is over size limit");
        }
        return false;
    }

    public String toString() {
        return "Generation#: " + genNum + ", Best Fitness: " + best.getFitness();
    }
}