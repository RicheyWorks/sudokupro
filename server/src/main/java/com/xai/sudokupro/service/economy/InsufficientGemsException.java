package com.xai.sudokupro.service.economy;

/** Thrown when a player tries to spend more gems than they hold. */
public class InsufficientGemsException extends RuntimeException {

    private final int balance;
    private final int cost;

    public InsufficientGemsException(String playerId, int balance, int cost) {
        super("Player " + playerId + " has " + balance + " gems but needs " + cost);
        this.balance = balance;
        this.cost = cost;
    }

    public int balance() { return balance; }
    public int cost()    { return cost; }
}
