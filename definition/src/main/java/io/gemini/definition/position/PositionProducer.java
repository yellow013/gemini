package io.gemini.definition.position;

@FunctionalInterface
public interface PositionProducer<T extends Position> {

	T produce(int accountId, int instrumentId, int qty);

}
