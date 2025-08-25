
package com.titiplex.budget.core.p2p;

import com.titiplex.budget.core.model.Op;

import java.util.List;
import java.util.function.Consumer;

public interface P2PService {
    void start(String groupName, String passphrase, List<String> seeds, int port, Consumer<Op> onOp);

    void broadcast(Op op);

    void stop();
}
