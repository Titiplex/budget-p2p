
package com.titiplex.budget.core.p2p;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titiplex.budget.core.crypto.CryptoBox;
import com.titiplex.budget.core.model.Expense;
import com.titiplex.budget.core.model.Op;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

@Service
public class JGroupsP2PService extends ReceiverAdapter implements P2PService {
    private final ObjectMapper mapper = new ObjectMapper();
    private JChannel ch;
    private String passphrase;
    private Consumer<Op> onOp;

    @Override
    public void start(String groupName, String passphrase, Consumer<Op> onOp) {
        try {
            this.passphrase = passphrase;
            this.onOp = onOp;
            ch = new JChannel(); // default UDP multicast on LAN
            ch.setReceiver(this);
            ch.connect(groupName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void receive(Message msg) {
        try {
            byte[] dec = CryptoBox.decrypt(passphrase, msg.getBuffer());
            String json = new String(dec, StandardCharsets.UTF_8);
            OpDTO dto = mapper.readValue(json, OpDTO.class);
            Op op = dto.toOp(mapper);
            onOp.accept(op);
        } catch (Exception e) {
            System.err.println("Failed to receive message from peer: " + e.getMessage());
        }
    }

    @Override
    public void broadcast(Op op) {
        try {
            OpDTO dto = OpDTO.fromOp(op, mapper);
            String json = mapper.writeValueAsString(dto);
            byte[] enc = CryptoBox.encrypt(passphrase, json.getBytes(StandardCharsets.UTF_8));
            Message m = new Message(null, enc);
            ch.send(m);
        } catch (Exception e) {
            System.err.println("Failed to broadcast message to peers: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (ch != null) ch.close();
    }

    static class OpDTO {
        public String type;
        public String payloadJson;

        static OpDTO fromOp(Op op, ObjectMapper mapper) throws Exception {
            OpDTO d = new OpDTO();
            d.type = op.type().name();
            d.payloadJson = mapper.writeValueAsString(op.payload());
            return d;
        }

        Op toOp(ObjectMapper mapper) throws Exception {
            Op.Type t = Op.Type.valueOf(type);
            Object p = switch (t) {
                case ADD, DELETE -> mapper.readValue(payloadJson, Expense.class);
            };
            return new Op(t, p);
        }
    }
}
