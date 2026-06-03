package com.poker.game;

/** Thrown when a player attempts an illegal poker action. The message is safe to show the user. */
public class InvalidActionException extends RuntimeException {
    public InvalidActionException(String message) {
        super(message);
    }
}
