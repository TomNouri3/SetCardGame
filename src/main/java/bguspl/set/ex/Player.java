package bguspl.set.ex;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.Action;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    // Added fields:

    public BlockingQueue<Integer> actions;
    private Dealer dealer;
    private volatile boolean shouldPenalty;
    private volatile boolean shouldRewarded;
    private volatile AtomicBoolean waitForAnswerAboutSet;

    // ------------------------------------------------------------------------------------------------------------------------

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;

        this.terminate = false;
        this.actions = new ArrayBlockingQueue<Integer>(env.config.featureSize, true);

        this.dealer = dealer;
        shouldPenalty = false;
        shouldRewarded = false;
        waitForAnswerAboutSet = new AtomicBoolean(false);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            Integer action = null;
            try{
                action = actions.take();
            }
            catch (InterruptedException e) {}

            table.beforePlayerAction();
            boolean putSet = false;
            if (action != null)
            {
                if(!table.removeToken(id, action))
                {
                    if(table.getPlayerCounter(id) != env.config.featureSize)
                    {
                        table.placeToken(id, action);
                        if(table.getPlayerCounter(id) == env.config.featureSize) // the player has set
                        {
                            waitForAnswerAboutSet.set(true);; // check
                            actions.clear(); // remove all elements from the queue.
                            try {
                                synchronized (this) {//----------------------------------------------
                                dealer.sets.put(id); // Submit the set to the dealer
                                putSet = true;
                                table.afterPlayerAction();
                                Thread dealerThread = dealer.getDealerThread(); // Get the dealer's thread
                                if (dealerThread != null) {
                                    synchronized (dealer.getLock()) {
                                        dealer.getLock().notifyAll(); // Correctly notify all threads waiting on this player's object monitor
                                    }
                                }
                                    wait();
                                }
                            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            if(shouldPenalty) // check
                            {
                                shouldPenalty = false;
                                penalty();
                            }
                            if(shouldRewarded) // check
                            {
                                shouldRewarded = false;
                                point();
                            }
                            waitForAnswerAboutSet.set(false); // check
                            if (!human)
                            {
                                synchronized(aiThread)
                                {
                                    aiThread.notifyAll();
                                }
                            }
                        }
                    }
                }
            }
            if(!putSet)
            {
                table.afterPlayerAction();
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {

                int simulatedAction = (int) (Math.random() * env.config.tableSize);
                keyPressed(simulatedAction);

            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        if(!human)
        {
            this.aiThread.interrupt();
        }
        playerThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if(table.getIsTableAvaliable() && !waitForAnswerAboutSet.get())
        {
            actions.offer(slot);
        }
        if (!human && waitForAnswerAboutSet.get()) {
            synchronized (aiThread) {
                try {
                    while (waitForAnswerAboutSet.get())
                    {
                        aiThread.wait();
                    }
                } catch (InterruptedException e) {Thread.currentThread().interrupt();}
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        try {
            int countdown = (int) env.config.pointFreezeMillis / 1000;
            for (int i = countdown; i > 0; i--)
            {
                env.ui.setFreeze(id, i * 1000);
                playerThread.sleep(1000);
            }
            env.ui.setFreeze(id, 0);
        } catch (InterruptedException e) {env.ui.setFreeze(id, 0);}
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        try {
            int countdown = (int) env.config.penaltyFreezeMillis / 1000;
            for (int i = countdown; i > 0; i--)
            {
                env.ui.setFreeze(id, i * 1000);
                playerThread.sleep(1000);
            }
            env.ui.setFreeze(id, 0);
        } catch (InterruptedException e) {env.ui.setFreeze(id, 0);}
    }

    public int score() {
        return score;
    }

    public Thread getPlayerThread()
    {
        return playerThread;
    }

    public void setShouldRewarded(boolean value)
    {
        shouldRewarded = value;
    }

    public void setShouldPenalty(boolean value)
    {
        shouldPenalty = value;
    }
}