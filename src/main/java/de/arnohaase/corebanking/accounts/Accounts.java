package de.arnohaase.corebanking.accounts;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.cluster.sharding.ClusterSharding;
import akka.cluster.sharding.ClusterShardingSettings;
import akka.cluster.sharding.ShardRegion;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;


public class Accounts extends AbstractActor {
    public static Props props(AtomicReference<ActorRef> transferWatchDog) {
        return Props.create(Accounts.class, () -> new Accounts(transferWatchDog));
    }

    private final AtomicReference<ActorRef> transferWatchDog;

    private final ShardRegion.MessageExtractor messageExtractor = new ShardRegion.HashCodeMessageExtractor(1000) {
        @Override public String entityId (Object message) {
            return ((AccountMessages.WithEntityId) message).entityId().toString();
        }
    };
    private final ActorRef shardRegion;

    public Accounts (AtomicReference<ActorRef> transferWatchdog) {
        this.transferWatchDog = transferWatchdog;
        shardRegion = ClusterSharding.get(context().system()).start(
                "accounts",
                AccountEntity.props(self(), transferWatchDog.get()),
                ClusterShardingSettings.create(context().system()),
                messageExtractor
        );
    }

    @Override public Receive createReceive () {
        return receiveBuilder()
                .match(AccountMessages.New.class, this::onNew)
                .match(AccountMessages.WithEntityId.class, msg -> shardRegion.forward(msg, context()))
                .build();
    }

    private void onNew(Object msg) {
        final UUID entityId = UUID.randomUUID();
        self().forward(ImmutableCreateAccount.of(entityId), context());
    }
}
