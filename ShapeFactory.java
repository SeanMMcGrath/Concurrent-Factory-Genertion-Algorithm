import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ThreadLocalRandom;


public class ShapeFactory extends Thread {

    //final and ThreadLocal<> for everything possible
    private final int[][] layout = new int[7][7];
    private boolean firstGen;

    private int generation;

    //number between 1 and 0 that denotes fitness of layout, higher is better. Can never reach 1, but in this implementation 0 is possible
    private double fitness;

    private CountDownLatch start;
    private BlockingQueue<String> BQ;
    private int n;
    private int m;

    private boolean crossover;//if true, do crossover
    private Exchanger<Integer> crossoverExchanger;
    private int crossoverRow;
    private int crossoverColumn;

    private volatile boolean running;

    /**
     *init layout and begin reproducing, testing with 7x7 square
     * @param n - number of shapes
     * @param m - number of spaces, assumed to always be a perfect square
     * @param start - when released this thread starts
     */
    public ShapeFactory(int n, int m, CountDownLatch start, BlockingQueue<String> BQ){
        this.n = n;
        this.m = m;
        this.start = start;
        this.BQ = BQ;
        firstGen = true;
        generation = 1;
        crossoverExchanger = null;
        crossover = false;
        running = true;
    }

    public void  stopRunning(){//end thread
        running = false;
    }

    public int getGenNum(){
        return generation;
    }

    public void run(){//run the thread... constantly evolves, waiting between each evolution to check with superclass on whether it needs to do crossover
        try{
            start.await();//sync initial run
            while(running) {
                if (firstGen) {//if initial generation, create itself
                    generate(n, m);
                    fitness = calculateFitness(layout);
                    firstGen = false; //no longer first gen
                } else {//evolve and crossover
                    generation++;
                    //do crossover

                    if (crossover) {
                        crossover();
                        crossoverExchanger = null;
                        crossover = false;
                        //turn croossover exchanger null after? also do a check for if null with sout
                    }
                    //mutation after crossover
                    evolve(10);
                }
                BQ.put(Thread.currentThread().getName());
                this.waitForNow();
            }
        }catch(InterruptedException e) {
            e.printStackTrace();
        }

    }

    /**
     * evolves by creating 10 children(one of which is itself) and randomly choosing one to survive(weighted by fitness) and sets those values to this factory
     * @param childrenNum - how many children to create, possibly not a variable number
     */
    private void evolve(int childrenNum){
        HashMap<Double, int[][]> children = new HashMap<>();
        children.put(this.fitness, this.layout);//add self to children(a form of elitism) to give some chance of continuity
        //helps stabilize randomness to keep progress, since a degressive evolution could mess with algorithm
        ArrayList<Double> fitnessList = new ArrayList<>();
        fitnessList.add(this.fitness);

        for(int i = 0; i < childrenNum; i++) {
            int[][] childLayout = layout;//init as parent layout, then change stations
            //now mutate it mutationCount times
            for (int x = 0; x < layout.length; x++) {
                for(int y = 0; y < layout[0].length; y++) {
                    ThreadLocal<Integer> mutationRate = new ThreadLocal<>();
                    mutationRate.set(ThreadLocalRandom.current().nextInt(1, 50));//1-49
                    if(mutationRate.get() == 1) {//1/n chance to mutate where n is the number of chromosomes(spots in layout) to mutate
                        //1/49 chance reached, so mutate randomly
                        int temp = ThreadLocalRandom.current().nextInt(0, 5);//0-4
                        childLayout[x][y] = temp;
                    }
                }
            }
            double childFitness = calculateFitness(childLayout);
            children.put(childFitness, childLayout);
            fitnessList.add(childFitness);
        }
        //now choose a child to keep and replace self, weighted towards better fitness
        Collections.sort(fitnessList);
        Collections.reverse(fitnessList);
        for(int i = 0; i < 4; i++){//remove 4 worst
            fitnessList.remove(fitnessList.size()-1);
        }

        double chosenFitness = rw_stochastic_selection(fitnessList);

        this.fitness = chosenFitness;//set new vars
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                this.layout[x][y] = children.get(chosenFitness)[x][y];
            }
        }

    }

    /**
     * uses Stochastic acceptance to find a weighted random individual
     * @param list - list of fitness's, sorted descendingly
     * @return - randomly selected individual, weighted towards better fitness
     */
    private double rw_stochastic_selection(ArrayList<Double> list) {
        ThreadLocal<Double> f_max = new ThreadLocal<>();
        f_max.set(list.get(0));//largest fitness
        for (;;) {
            // Select randomly one of the individuals
            ThreadLocal<Double> i = new ThreadLocal<>();
            i.set(list.get(ThreadLocalRandom.current().nextInt(0,6)));

            // The selection is accepted with probability fitness(i) / f_max
            if (ThreadLocalRandom.current().nextDouble(0, 1) < (i.get() / f_max.get())){
                return i.get();
            }
        }
    }

    /**
     * Does crossover with partner by swaping the station at RowXColumn with predetermined partner
     */
    private void crossover() throws InterruptedException {
        int send = layout[crossoverRow][crossoverColumn];
        int retrieve = crossoverExchanger.exchange(send);
        layout[crossoverRow][crossoverColumn] = retrieve;
    }

    private int[][] generate(int n, int m){
        int square = (int)Math.sqrt(m);
        //initialize layout
        //int[][] layout = new int[square][square];

        ThreadLocal<Integer> emptyCount = new ThreadLocal<>(); //count of how many empty spots there have been made
        emptyCount.set(0);
        ThreadLocal<Integer> shapeCount = new ThreadLocal<>();
        shapeCount.set(0);

        for (int i = 0; i < square; i++) {
            for (int j = 0; j < square; j++) {
                final int chosen;//randomly chosen shape(or empty) if empty spots maxed
                if (emptyCount.get() == m - n && shapeCount.get() != n) {//empty spaces maxed out, or max shapes placed
                    //random, bound by (0-3)
                    chosen = ThreadLocalRandom.current().nextInt(0, 4);
                    shapeCount.set(shapeCount.get()+1);
                } else if (shapeCount.get() == n) {
                    chosen = 4;
                    emptyCount.set(emptyCount.get() + 1);
                } else {
                    //random, bound by (0-4)
                    chosen = ThreadLocalRandom.current().nextInt(0, 5);

                    if (chosen == 4) {//chosen is empty space so increment empty count
                        emptyCount.set(emptyCount.get() + 1);
                    } else {
                        shapeCount.set(shapeCount.get()+1);
                    }
                }
                //put in layout
                layout[i][j] = chosen;
            }
        }
        return layout;
    }

    /**
     * Used for creating a snapshot of a factory
     * @param layout
     * @param fitness
     */
    public ShapeFactory(int[][] layout, double fitness){
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                this.layout[x][y] = layout[x][y];
            }
        }
        this.fitness = fitness;
    }

    /**
     *
     * @return a snapshot of the current factory
     */
    public ShapeFactory getSnapshot(){
        return new ShapeFactory(layout, fitness);
    }

    /**
     * calculates normalized fitness of the individual and sets it to int fitness
     * metric is basically +1 for flat sides touching ie square next to square and -1 for anything else like trinagle point next to square side
     * besides circles, all --'s and ++'s are done twice which evens out
     * fitness:    add up for each position and normalize between 0 and 1(but not reaching either)
     * triangle(0): +1 for square bellow it, -1 for square above it or on sides, +1 for each upside-down triangle on side
     * upside-down triangle(1): +1 for square above it, -1 for square bellow it or on sides, +1 for each triangle on sides
     * square(2): +1 if triangle above or reverse triangle bellow, +1 for each square next to it, -1 for triangles on side or triangle bellow/reverse triangle above
     * circle(3): -1 for each shape next to it, +5 if nothing borders it
     * 4 is empty space and is ignored by fitness
     *
     * done after each evolution
     * returned fitness is normalized
     */
    public double calculateFitness(int[][] layout){
        ThreadLocal<Double> initialFitness = new ThreadLocal<>();
        initialFitness.set(0.0);
        for (int x = 0; x < layout.length; x++) {
            for (int y = 0; y < layout[0].length; y++) {

                if (layout[x][y] == 0) {//triangle
                    if(x != 0){
                        if(layout[x-1][y] == 1){
                            initialFitness.set(initialFitness.get()+1);//reverse triangle left
                        }
                    }
                    if(y != 0){
                        if(layout[x][y-1] == 2 || layout[x][y-1] == 0 || layout[x][y-1] == 1){
                            initialFitness.set(initialFitness.get()+1);//square or triangle bellow
                        }
                    }
                    if(x != layout.length-1){
                        if(layout[x+1][y] == 1){//reverse triangle right
                            initialFitness.set(initialFitness.get()+1);
                        }
                    }
                } else if (layout[x][y] == 1) {//upsideDown triangle
                    if(x != 0){
                        if(layout[x-1][y] == 0){
                            initialFitness.set(initialFitness.get()+1);//triangle left
                        }
                    }
                    if(x != layout.length-1){
                        if(layout[x+1][y] == 0){//triangle right
                            initialFitness.set(initialFitness.get()+1);
                        }
                    }
                    if(y != layout.length-1){
                        if(layout[x][y+1] == 2 || layout[x][y+1] == 0){//square or triangle above
                            initialFitness.set(initialFitness.get()+1);
                        }
                    }

                } else if (layout[x][y] == 2) {//square
                    if(x != 0){
                        if(layout[x-1][y] == 2){
                            initialFitness.set(initialFitness.get()+1);//square left
                        }
                    }
                    if(y != 0){
                        if(layout[x][y-1] == 2 || layout[x][y-1] == 1){
                            initialFitness.set(initialFitness.get()+1);//square bellow or reverse triangle
                        }
                    }
                    if(x != layout.length-1){
                        if(layout[x+1][y] == 2){//square right
                            initialFitness.set(initialFitness.get()+1);
                        }
                    }
                    if(y != layout.length-1){
                        if(layout[x][y+1] == 2 || layout[x][y+1] == 0){//square or triangle above
                            initialFitness.set(initialFitness.get()+1);
                        }
                    }
                } else if (layout[x][y] == 3) {//circle (more or less disconnected from other shapes, works well alone)
                    boolean flag = false;//false if no neighbors so +5 to fitness
                    if(x != 0){
                        if(layout[x-1][y] != 4){
                            flag = true;
                        }
                    }
                    if(y != 0){
                        if(layout[x][y-1] != 4){
                            flag = true;
                        }
                    }
                    if(x != layout.length-1){
                        if(layout[x+1][y] != 4){
                            flag = true;
                        }
                    }
                    if(y != layout.length-1){
                        if(layout[x][y+1] != 4){//not blank space
                            flag = true;
                        }
                    }

                    if (!flag) {//nothing bordering so +5
                        initialFitness.set(initialFitness.get()+5);
                    }
                } //else its a space so ignore it(4)
            }
        }

        //normalize then return
        return normalize(initialFitness);
    }

    /*
    normalized between 0 and 1, with max value of 196 because it is unreachable in the 7x7 square i am using with my metrics, while also being within reason
    (pretends all stations can have 4 points even though it is unfeasible)
     */
    private double normalize(ThreadLocal<Double> fitness){
        return fitness.get() / 168;
    }

    public void readyCrossover(Exchanger e, int row, int column){
        crossover = true;
        crossoverExchanger = e;
        crossoverRow = row;
        crossoverColumn = column;
    }


    boolean waiting = false;

    private synchronized void waitForNow() throws InterruptedException {
        waiting = true;
        while(waiting) {
            this.wait();
        }
    }
    public synchronized void free(){
        waiting = false;
        this.notify();
    }

    public int[][] getLayout(){
        return layout;
    }

    public double getFitness(){
        return fitness;
    }
}
