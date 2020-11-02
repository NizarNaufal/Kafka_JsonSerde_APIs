package id.kafka.learn

import id.kafka.learn.json.produceRandomJsonGamePrice
import id.kafka.learn.json.JsonSerde.makeJsonSerde
import id.kafka.learn.KafkaConfig.BOOTSTRAP_SERVERS
import id.kafka.learn.KafkaConfig.STORE
import id.kafka.learn.KafkaConfig.TOPIC_INPUT
import id.kafka.learn.KafkaConfig.TOPIC_OUTPUT
import id.kafka.learn.KafkaConfig.addConfluentCloudConfig
import id.kafka.learn.KafkaConfig.createTopicsIfNecessary
import id.kafka.learn.model.GamePrice
import id.kafka.learn.model.Price
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.*
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.Produced
import org.apache.kafka.streams.kstream.Transformer
import org.apache.kafka.streams.kstream.TransformerSupplier
import org.apache.kafka.streams.processor.ProcessorContext
import org.apache.kafka.streams.processor.WallclockTimestampExtractor
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.Stores
import java.util.*



object ChangedPricesStream {

    @JvmStatic
    fun main(args: Array<String>) {
        createTopicsIfNecessary()
        produceRandomJsonGamePrice()

        val streams = KafkaStreams(
            buildTopology(
                StreamsBuilder()
            ), Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, "changed-prices")
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS)
            addConfluentCloudConfig()
        })

        streams.cleanUp()
        streams.start()

        Runtime.getRuntime().addShutdownHook(Thread(streams::close))
    }

    // visible for testing
    fun buildTopology(sb: StreamsBuilder): Topology {
        val serdeSource = Consumed.with(
            Serdes.String(),
            makeJsonSerde<GamePrice>(),
            WallclockTimestampExtractor(),
            Topology.AutoOffsetReset.EARLIEST
        )
        val sinkSerde = Produced.with(Serdes.String(), makeJsonSerde<GamePrice>())


        // make a state to keep our price we're going to compare to
        sb.addStateStore(
            Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STORE),
                Serdes.StringSerde(),
                Serdes.DoubleSerde()
            )
        )

        // the real deal: the topology
        sb.stream(TOPIC_INPUT, serdeSource)
            .transform(makeDedupTransformer(), STORE)
            .to(TOPIC_OUTPUT, sinkSerde)

        return sb.build()
    }

    private fun makeDedupTransformer(): TransformerSupplier<String, GamePrice, KeyValue<String, GamePrice>> =
        TransformerSupplier {
            object : Transformer<String, GamePrice, KeyValue<String, GamePrice>> {
                private lateinit var context: ProcessorContext
                private lateinit var store: KeyValueStore<String, Price>

                @Suppress("UNCHECKED_CAST")
                override fun init(context: ProcessorContext) {
                    this.context = context
                    this.store = context.getStateStore(STORE) as KeyValueStore<String, Price>
                }

                override fun transform(key: String, value: GamePrice): KeyValue<String, GamePrice>? {
                    val existingKey: Price? = store.get(key)
                    return when {
                        existingKey == null || existingKey != value.price -> {
                            store.put(key, value.price)
                            KeyValue.pair(key, value)
                        }
                        else -> null
                    }
                }

                override fun close() {}
            }
        }
}