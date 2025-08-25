package com.titiplex.budget.core.fx;

import com.titiplex.budget.core.crdt.HLC;
import com.titiplex.budget.core.crypto.SessionState;
import com.titiplex.budget.core.model.FxRate;
import com.titiplex.budget.core.model.Op;
import com.titiplex.budget.core.p2p.P2PService;
import com.titiplex.budget.core.store.Repository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class FxAutoService {
    private final Repository repo;
    private final P2PService p2p;
    private final SessionState ss;

    @Value("${app.fx.auto:true}")
    private boolean auto;
    @Value("${app.fx.refresh.hours:24}")
    private int refreshHours;
    @Value("${app.fx.source:ecb}")
    private String source; // ecb
    @Value("${app.fx.base:EUR}")
    private String base;     // base logique (EUR)

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private ScheduledExecutorService ses;
    private HLC.Clock clock;

    public FxAutoService(Repository repo, P2PService p2p, SessionState ss) {
        this.repo = repo;
        this.p2p = p2p;
        this.ss = ss;
    }

    public void startScheduler() {
        if (!auto) return;
        if (clock == null) clock = new HLC.Clock(ss.userId != null ? ss.userId : UUID.randomUUID().toString());
        if (ses == null) {
            ses = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fx-auto");
                t.setDaemon(true);
                return t;
            });
            // 0 = maintenant, puis toutes refreshHours
            ses.scheduleAtFixedRate(this::safeFetchOnce, 0, Math.max(1, refreshHours), TimeUnit.HOURS);
        }
    }

    public void fetchNow() {
        safeFetchOnce();
    }

    private void safeFetchOnce() {
        try {
            Map<String, BigDecimal> map = switch (source.toLowerCase()) {
                case "ecb" -> fetchFromEcb();
                default -> fetchFromEcb();
            };
            if (map == null || map.isEmpty()) return;

            // Force la base à 1.0
            map.put(base.toUpperCase(Locale.ROOT), BigDecimal.ONE);

            for (var e : map.entrySet()) {
                String ver = clock.tick();
                FxRate r = new FxRate(e.getKey().toUpperCase(Locale.ROOT), e.getValue(), false, ver, ss.userId);
                repo.upsertFx(r);
                p2p.broadcast(new Op(Op.Type.FX_UPSERT, r));
            }
        } catch (Exception ignored) {
        }
    }

    private Map<String, BigDecimal> fetchFromEcb() throws Exception {
        // Flux officiel EUR-base publié ~16:00 CET chaque jour ouvré
        var req = HttpRequest.newBuilder(URI.create("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"))
                .GET().timeout(Duration.ofSeconds(15)).build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;

        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(resp.body())));
        NodeList cubes = doc.getElementsByTagName("Cube");
        Map<String, BigDecimal> out = new HashMap<>();
        for (int i = 0; i < cubes.getLength(); i++) {
            Node n = cubes.item(i);
            if (n instanceof Element el && el.hasAttribute("currency") && el.hasAttribute("rate")) {
                String code = el.getAttribute("currency").trim();
                String rate = el.getAttribute("rate").trim(); // 1 EUR = rate * CODE
                if (!code.isEmpty() && !rate.isEmpty()) {
                    out.put(code, new BigDecimal(rate));
                }
            }
        }
        return out;
    }
}