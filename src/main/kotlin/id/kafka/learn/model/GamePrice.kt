package id.kafka.learn.model


typealias GameId = String
typealias Price = Double

data class GamePrice(val gameId: GameId, val price: Price)