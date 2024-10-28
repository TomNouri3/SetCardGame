package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    // Added fields:
    public BlockingQueue<Integer> sets;
    private final Object lock;
    private Thread dealerThread;
    private Thread[] playersOfThreads;
    boolean placeAll;
    int[] slotsOfPlayer;


    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());

        this.sets = new ArrayBlockingQueue<Integer>(env.config.players, true);
        lock = new Object();
        dealerThread = Thread.currentThread();
        playersOfThreads = new Thread[players.length];
        placeAll = true;
        slotsOfPlayer = null;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");

        for(int i = 0; i < playersOfThreads.length; i++)
        {
            playersOfThreads[i] = new Thread(players[i]);
            playersOfThreads[i].start();
        }



        while (!shouldFinish())
        {
            placeAll = true;
            table.beforeDealerAction();
            placeCardsOnTable();
            table.afterDealerAction();
            placeAll = false;
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 99; //----------------------------------------------------------------------------------------------------
            table.setTableAvaliable(true);
            timerLoop();
            updateTimerDisplay(true);
            table.setTableAvaliable(false);
            table.beforeDealerAction();
            removeAllCardsFromTable();
            table.afterDealerAction();

        }

        if(terminate == false){
            terminate();
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            table.setTableAvaliable(true);
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            table.setTableAvaliable(false);
            table.beforeDealerAction();
            removeCardsFromTable();
            placeCardsOnTable();
            table.afterDealerAction();

        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for (int i = players.length-1; i >= 0; i--)
        {
            players[i].terminate();
            try {
                players[i].getPlayerThread().join();
            } catch (InterruptedException ignored) {}
        }
        terminate =  true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        while(!sets.isEmpty())
        {
            Integer idOfPlayer = sets.poll();
            if (idOfPlayer != null)
            {
                Player currPlayer = players[idOfPlayer];
                if(table.getPlayerCounter(idOfPlayer) == 3)
                {
                    boolean isValidSet = env.util.testSet(table.getPlayerCards(idOfPlayer));
                    if(isValidSet)
                    {
                        slotsOfPlayer = table.getPlayerSlots(idOfPlayer);
                        for(int slot : slotsOfPlayer)
                        {
                            table.removeCard(slot);
                            for(Player tempPlayer : players)
                            {
                                tempPlayer.actions.remove(slot);
                            }
                        }
                        currPlayer.setShouldRewarded(true);
                        placeAll = false;
                        placeCardsOnTable();
                        updateTimerDisplay(isValidSet);
                    }
                    else
                    {
                        currPlayer.setShouldPenalty(true);
                    }
                }
                currPlayer.actions.clear();
                synchronized (currPlayer) {
                    currPlayer.notifyAll();
                }
            }
        }

    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        Collections.shuffle(deck);
        if(placeAll)
        {
            for(int i = 0; i < env.config.tableSize && deck.size() > 0; i++)
            {
                table.placeCard(deck.remove(0), i);
            }
        }
        else
        {
            if(slotsOfPlayer != null)
            {

                for(int i = 0; i < env.config.featureSize && deck.size() > 0; i++)
                {
                    table.placeCard(deck.remove(0), slotsOfPlayer[i]);
                }
                slotsOfPlayer = null;

            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    public void sleepUntilWokenOrTimeout() {
        if(sets.size() == 0)
        {
            synchronized (lock)
            {
                try {
                    lock.wait(1);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset)
    {
        long countDown = reshuffleTime - System.currentTimeMillis();
        if(reset)
        {
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 99; //----------------------------------------------------------------------------------------------------
        }
        else
        {
            if(countDown >= env.config.turnTimeoutWarningMillis)
                env.ui.setCountdown(countDown , false);
            else
                env.ui.setCountdown(countDown , true);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        for(int i = 0; i < env.config.tableSize; i++)
        {
            int card = table.cardInSlot(i);
            if(card != -1)
            {
                deck.add(card);
                table.removeCard(i);
            }
        }
        sets.clear();
        for(Player player : players)
        {
            player.actions.clear();

            synchronized (player) {
                player.notifyAll();
            }

            player.setShouldPenalty(false);
            player.setShouldRewarded(false);
            player.getPlayerThread().interrupt();
        }

    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners()
    {
        // If there are no players, there's nothing to do.
        if (players == null || players.length == 0)
        {
            env.logger.info("No players in the game.");
            return;
        }

        // Initialize maxScore to the minimum possible value.
        int maxScore = -1;

        // Use a list to collect the IDs of the winner(s) as there might be a tie.
        List<Integer> winnerIds = new ArrayList<>();

        // Iterate over all players to find the maximum score and collect winner IDs.
        for (Player player : players)
        {
            int playerScore = player.score();
            // Update maxScore and reset winnerIds list if a new maxScore is found.
            if (playerScore > maxScore)
            {
                maxScore = playerScore;
                winnerIds.clear();
                winnerIds.add(player.id);
            }
            else if (playerScore == maxScore)
            {
                // If the current player has a score equal to maxScore, add their ID to the winnerIds list.
                winnerIds.add(player.id);
            }
        }

        // Convert the list of winner IDs to an array and pass it to the announceWinner method.
        int[] winnersArray = new int[winnerIds.size()];
        for (int i = 0; i < winnerIds.size(); i++)
        {
            winnersArray[i] = winnerIds.get(i); // Unbox Integer to int
        }
        env.ui.announceWinner(winnersArray);
    }

    public Thread getDealerThread() {
        return dealerThread;
    }

    public Object getLock(){
        return lock;
    }

    public void addToDeck(int card)
    {
        deck.add(card);
    }
}