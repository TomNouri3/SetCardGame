package bguspl.set.ex;
// עבודה חדשה
import bguspl.set.Env;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    // Added fields:
    private volatile AtomicBoolean isTableAvaliable;
    private int[][] tokenOnTable;
    private int[] tokenCounterOfPlayers;
    private int activePlayers;
    private int activeDealer;
    private int waitingDealer;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;

        isTableAvaliable = new AtomicBoolean(false);
        this.tokenOnTable = new int[env.config.players][env.config.featureSize];
        for(int i = 0; i < tokenOnTable.length; i++)
        {
            for(int j = 0; j < tokenOnTable[i].length; j++)
            {
                tokenOnTable[i][j] = -1;
            }
        }
        tokenCounterOfPlayers = new int[env.config.players];
        for(int element : tokenCounterOfPlayers)
        {
            element = 0;
        }
        this.activePlayers = 0;
        this.activeDealer = 0;
        this.waitingDealer = 0;
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        if (slotToCard[slot] != null)
        {
            int card = slotToCard[slot];
            cardToSlot[card] = null;
            slotToCard[slot] = null;
            env.ui.removeCard(slot);

            for (int i = 0; i < tokenOnTable.length; i++)
            {
                removeToken(i,slot);
            }
            // romove token in queue to all players
        }
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) { //------------------------------------------------------------------------------------
        if(slotToCard[slot] != null && tokenCounterOfPlayers[player] < env.config.featureSize)
        {
            boolean done = false;
            for (int i = 0; i < env.config.featureSize && !done; i++)
            {
                if(tokenOnTable[player][i] == -1)
                {
                    tokenOnTable[player][i] = slot;
                    tokenCounterOfPlayers[player] ++;
                    done = true;
                    env.ui.placeToken(player, slot);
                }
            }
        }
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) { //------------------------------------------------------------------------------------

        for(int i = 0; i < env.config.featureSize; i++)
        {
            if (tokenOnTable[player][i] == slot)
            {
                env.ui.removeToken(player, slot);
                tokenOnTable[player][i] = -1;
                tokenCounterOfPlayers[player] --;
                return true;
            }
        }
        return false;
    }

    // Added methods:

    public int cardInSlot(int slot)
    {
        if(slotToCard[slot] != null)
        {
            return slotToCard[slot];
        }
        return -1;
    }

    public int SlotOfcard(int card)
    {
        if(cardToSlot[card] != null)
        {
            return cardToSlot[card];
        }
        return -1;
    }

    public boolean getIsTableAvaliable()
    {
        return isTableAvaliable.get();
    }

    public void setTableAvaliable(boolean isAvaliable)
    {
        isTableAvaliable.set(isAvaliable);
    }

    public int getPlayerCounter(int id)
    {
        return tokenCounterOfPlayers[id];
    }

    public int[] getPlayerCards(int id)
    {
        int[] arr = new int[env.config.featureSize];
        for(int i = 0; i < env.config.featureSize; i++)
        {
            arr[i] = slotToCard[tokenOnTable[id][i]];
        }
        return arr;
    }

    public int[] getPlayerSlots(int id)
    {
        int[] arr = new int[env.config.featureSize];
        for(int i = 0; i < env.config.featureSize; i++)
        {
            arr[i] = tokenOnTable[id][i];
        }
        return arr;
    }

    public synchronized void beforePlayerAction()
    {
        while (!(waitingDealer == 0 && activeDealer == 0))
        {
            try
            {
                wait();
            }
            catch (InterruptedException ignored){}
        }
        activePlayers++;
    }

    public synchronized void afterPlayerAction()
    {
        activePlayers--;
        notifyAll();
    }

    public synchronized void beforeDealerAction()
    {
        waitingDealer++;
        while (!(activePlayers == 0 && activeDealer == 0))
        {
            try
            {
                wait();
            }
            catch (InterruptedException ignored){}
        }
        waitingDealer--;
        activeDealer++;
    }

    public synchronized void afterDealerAction()
    {
        activeDealer--;
        notifyAll();
    }
}