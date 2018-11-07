package de.arnohaase.corebanking.watchdog;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.cluster.sharding.ClusterSharding;
import akka.cluster.sharding.ClusterShardingSettings;
import akka.cluster.sharding.ShardRegion;
import de.arnohaase.corebanking.accounts.AccountMessages;


public class TransferWatchDog extends AbstractActor {
    private final ActorRef shardRegion;

    public static Props props(ActorRef accounts) {
        return Props.create(TransferWatchDog.class, () -> new TransferWatchDog(accounts));
    }

    private TransferWatchDog (ActorRef accounts) {
        final ShardRegion.MessageExtractor messageExtractor = new ShardRegion.HashCodeMessageExtractor(32) {
            @Override
            public String entityId (Object msg) {
                final byte[] bytes = new byte[1];

                if (msg instanceof AccountMessages.TransferCommand)
                    bytes[0] = (byte) (((AccountMessages.TransferCommand) msg).transferId().getLeastSignificantBits() & 32);
                else if (msg instanceof AccountMessages.TransferPingCancellation)
                    bytes[0] = (byte) (((AccountMessages.TransferPingCancellation) msg).transferId().getLeastSignificantBits() & 32);
                else if (msg instanceof Byte)
                    bytes[0] = (Byte)msg;
                else
                    bytes[0] = 0;

                return new String(bytes);
            }
        };
        
        shardRegion = ClusterSharding.get(context().system()).start(
                "transfer-watchdog",
                TransferWatchDogEntity.props(accounts),
                ClusterShardingSettings.create(context().system()),
                messageExtractor
        );

        for (byte b=0; b<32; b++) self().tell(b, ActorRef.noSender());
    }

    @Override public Receive createReceive () {
        return receiveBuilder()
                .matchAny(msg -> shardRegion.forward(msg, context()))
                .build();
    }
}
