package de.arnohaase.corebanking.watchdog;

import akka.Done;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.persistence.AbstractPersistentActorWithTimers;
import de.arnohaase.corebanking.accounts.AccountMessages;
import de.arnohaase.corebanking.accounts.ImmutableTransferCommand;
import de.arnohaase.corebanking.accounts.ImmutableTransferPing;
import scala.concurrent.duration.FiniteDuration;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


public class TransferWatchDogEntity extends AbstractPersistentActorWithTimers {
    private final ActorRef accounts;
    private final Map<UUID, AccountMessages.TransferPing> watchList = new HashMap<>();

    public static Props props(ActorRef accounts) {
        return Props.create(TransferWatchDogEntity.class, () -> new TransferWatchDogEntity(accounts));
    }

    public TransferWatchDogEntity (ActorRef accounts) {
        this.accounts = accounts;
        timers().startPeriodicTimer("", "ping", FiniteDuration.apply(5, TimeUnit.MINUTES));
    }

    @Override public String persistenceId () {
        return "transfer-watchdog-" + self().path().name();
    }

    @Override public Receive createReceive () {
        return receiveBuilder()
                .match(AccountMessages.TransferCommand.class, this::onStartWatch)
                .match(AccountMessages.TransferPingCancellation.class, this::onCancelWatch)
                .matchEquals("ping", this::doPing)
                .build();
    }

    private void doPing(Object msg) {
        for (AccountMessages.TransferPing tp: watchList.values()) {
            accounts.tell(tp, self());
        }
    }

    private void onStartWatch(AccountMessages.TransferCommand msg) {
        if (! watchList.containsKey(msg.transferId())) {
            persist(ImmutableTransferPing.of(msg.transferId(), msg.entityId()), evt -> {
                watchList.put(msg.transferId(), evt);
                sender().tell(ImmutableTransferCommand.copyOf(msg).withIsWatched(true), self());
            });
        }
    }
    private void onCancelWatch(AccountMessages.TransferPingCancellation msg) {
        if (watchList.containsKey(msg.transferId())) {
            persist(msg, evt -> {
                watchList.remove(msg.transferId());
            });
        }
    }

    @Override public Receive createReceiveRecover () {
        return receiveBuilder()
                .match(AccountMessages.TransferPing.class, evt -> watchList.put(evt.transferId(), evt))
                .match(AccountMessages.TransferPingCancellation.class, evt -> watchList.remove(evt.transferId()))
                .build();
    }
}
