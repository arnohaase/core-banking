package de.arnohaase.corebanking;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import de.arnohaase.corebanking.accounts.Accounts;
import de.arnohaase.corebanking.watchdog.TransferWatchDog;

import java.util.concurrent.atomic.AtomicReference;


public class Main {
    public static void main (String[] args) {
        final var system = ActorSystem.create("core-banking");

        final var transferWatchDog = new AtomicReference<ActorRef>();
        final var accounts = system.actorOf(Accounts.props(transferWatchDog), "accounts");
        transferWatchDog.set(system.actorOf(TransferWatchDog.props(accounts), "transfer-watchdog"));

        final String host = system.settings().config().getString("core-banking.http-server.host");
        final int port = system.settings().config().getInt("core-banking.http-server.port");
        system.actorOf(HttpServer.props(host, port, accounts));
    }
}
