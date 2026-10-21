/* SPDX-License-Identifier: MPL-2.0 */
package com.mirth.connect.plugins.python.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.mirth.connect.donkey.server.channel.Channel;
import com.mirth.connect.donkey.server.channel.DestinationChainProvider;

class StockDestinationSelectionTest {
    private static Channel channel() {
        Channel channel = new Channel();
        var first = new DestinationChainProvider();
        var writer = new PythonDispatcher();
        assertFalse(writer.isEnabled(), "Stock runtime destination flag defaults to false");
        first.addDestination(1, writer);
        var second = new DestinationChainProvider();
        second.addDestination(3, new PythonDispatcher());
        channel.addDestinationChainProvider(first);
        channel.addDestinationChainProvider(second);
        return channel;
    }

    @Test void deployedMembershipIncludesStockDestinationsWhoseUnusedFlagIsFalse() {
        assertEquals(Set.of(1, 3), Connectors.destinationIds(channel()));
    }

    @Test void readerAllowsDeployedSubsetAndRejectsMissingDisabledModelDestinations() {
        Set<Integer> ids = Connectors.destinationIds(channel());
        Map<String, Object> message = Map.of("payload", "sample", "sourceMap", Map.of(), "destinationIds", List.of(1));
        var result = Connectors.readerMessages(Map.of("messages", List.of(message)), ids, 1000, false);
        assertEquals(List.of(1), result.get(0).getDestinationMetaDataIds());
        Map<String, Object> missing = Map.of("payload", "sample", "sourceMap", Map.of(), "destinationIds", List.of(2));
        assertThrows(Wire.ValueException.class, () -> Connectors.readerMessages(Map.of("messages", List.of(message, missing)), ids, 1000, false));
    }
}
