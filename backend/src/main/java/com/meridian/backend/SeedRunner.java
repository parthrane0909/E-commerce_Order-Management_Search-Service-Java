package com.meridian.backend;

import com.meridian.backend.common.util.DateUtils;
import com.meridian.backend.common.util.MoneyUtils;
import com.meridian.backend.orders.entity.Order;
import com.meridian.backend.orders.entity.OrderItem;
import com.meridian.backend.orders.entity.User;
import com.meridian.backend.orders.repository.OrderRepository;
import com.meridian.backend.orders.repository.UserRepository;
import com.meridian.backend.products.document.ProductDocument;
import com.meridian.backend.products.repository.ProductRepository;
import com.meridian.backend.search.dto.ElasticsearchOrderDocument;
import com.meridian.backend.search.repository.OrderSearchRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Profile("seed")
@RequiredArgsConstructor
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderSearchRepository searchRepository;
    private final PasswordEncoder passwordEncoder;

    private static final BigDecimal CENT = BigDecimal.valueOf(0.01);

    private static final List<Map<String, Object>> USERS = List.of(
        Map.of("name", "John Doe", "email", "john.doe@example.com"),
        Map.of("name", "Jane Smith", "email", "jane.smith@example.com"),
        Map.of("name", "Wendy Wireless", "email", "wendy.wireless@example.com"),
        Map.of("name", "Alex Rivera", "email", "alex.rivera@example.com"),
        Map.of("name", "Sam Patel", "email", "sam.patel@example.com"),
        Map.of("name", "Casey Nguyen", "email", "casey.nguyen@example.com"),
        Map.of("name", "Morgan Lee", "email", "morgan.lee@example.com"),
        Map.of("name", "Riley Brooks", "email", "riley.brooks@example.com")
    );

    /**
     * Artwork shipped with the frontend under {@code frontend/public/products/<SKU>.svg}.
     * The backend cannot see that directory, so seeded products are pointed at the URL by
     * convention. {@code CHG-001} has no dedicated drawing and uses the generic placeholder.
     */
    private static final Set<String> SKU_WITHOUT_ARTWORK = Set.of("CHG-001");
    private static final String DEFAULT_ARTWORK_URL = "/products/default.svg";

    private static final List<Map<String, Object>> PRODUCTS = List.of(
        Map.of("sku", "WM-001", "title", "Wireless Mouse", "description", "Ergonomic 2.4GHz wireless mouse with silent clicks and a 1600 DPI optical sensor.", "price", 50.16, "category", "peripherals", "tags", List.of("wireless", "usb", "office", "ergonomic"), "attributes", Map.of("color", "black", "dpi", 1600, "battery", "AA", "connection", "2.4GHz"), "variants", List.of(Map.of("sku", "WM-001-BLK", "color", "black", "stock", 40), Map.of("sku", "WM-001-WHT", "color", "white", "stock", 25)), "active", true),
        Map.of("sku", "MK-001", "title", "Mechanical Keyboard", "description", "Tenkeyless mechanical keyboard with hot-swappable red switches and per-key lighting.", "price", 129.00, "category", "peripherals", "tags", List.of("mechanical", "wired", "rgb", "keyboard"), "attributes", Map.of("layout", "TKL", "switch", "red linear", "backlight", "per-key RGB"), "variants", List.of(Map.of("sku", "MK-001-RED", "color", "dark grey", "stock", 30)), "active", true),
        Map.of("sku", "HUB-001", "title", "USB-C Hub 7-in-1", "description", "Aluminium USB-C hub with HDMI 4K, 100W power delivery, SD card and 3x USB 3.0.", "price", 45.50, "category", "peripherals", "tags", List.of("usb-c", "hub", "aluminium", "travel"), "attributes", Map.of("ports", 7, "hdmi", "4K@60Hz", "power_delivery_w", 100), "variants", List.of(Map.of("sku", "HUB-001-SLV", "color", "silver", "stock", 60)), "active", true),
        Map.of("sku", "VM-002", "title", "Vertical Ergonomic Mouse", "description", "Wired vertical mouse that keeps the wrist in a natural handshake position.", "price", 22.40, "category", "peripherals", "tags", List.of("ergonomic", "wired", "office"), "attributes", Map.of("color", "graphite", "dpi", 1200, "hand", "right"), "variants", List.of(Map.of("sku", "VM-002-GRF", "color", "graphite", "stock", 35)), "active", true),
        Map.of("sku", "CAM-001", "title", "HD Webcam Pro", "description", "1080p60 webcam with auto-focus, dual microphones and a privacy shutter.", "price", 89.99, "category", "peripherals", "tags", List.of("webcam", "usb", "video", "conference"), "attributes", Map.of("resolution", "1080p60", "microphones", "dual", "mount", "clip"), "variants", List.of(Map.of("sku", "CAM-001-BLK", "color", "black", "stock", 22)), "active", true),
        Map.of("sku", "PAD-001", "title", "Gaming Mousepad XL", "description", "900x400mm stitched-edge cloth mousepad with a non-slip rubber base.", "price", 18.99, "category", "peripherals", "tags", List.of("mousepad", "desk", "gaming"), "attributes", Map.of("size", "900x400mm", "surface", "cloth", "thickness_mm", 4), "variants", List.of(Map.of("sku", "PAD-001-BLK", "color", "black", "stock", 120)), "active", true),
        Map.of("sku", "PRS-001", "title", "Wireless Presenter Remote", "description", "2.4GHz presenter with red laser pointer, 30m range and plug-and-play USB receiver.", "price", 34.95, "category", "peripherals", "tags", List.of("wireless", "presenter", "office", "laser"), "attributes", Map.of("range_m", 30, "laser", "red", "battery", "2xAAA"), "variants", List.of(Map.of("sku", "PRS-001-BLK", "color", "black", "stock", 45)), "active", true),
        Map.of("sku", "KBD-002", "title", "Wireless Keyboard Slim", "description", "Low-profile wireless keyboard with multi-device Bluetooth pairing.", "price", 89.00, "category", "peripherals", "tags", List.of("wireless", "bluetooth", "keyboard", "low-profile"), "attributes", Map.of("layout", "full size", "battery", "rechargeable", "devices", 3), "variants", List.of(Map.of("sku", "KBD-002-GRY", "color", "grey", "stock", 28)), "active", true),
        Map.of("sku", "EAR-001", "title", "Wireless Earbuds", "description", "True wireless earbuds with 24h battery, USB-C case and IPX5 water resistance.", "price", 79.99, "category", "audio", "tags", List.of("wireless", "bluetooth", "in-ear", "sport"), "attributes", Map.of("battery_hours", 24, "codec", "AAC", "rating", "IPX5"), "variants", List.of(Map.of("sku", "EAR-001-BLK", "color", "black", "stock", 55), Map.of("sku", "EAR-001-WHT", "color", "white", "stock", 33)), "active", true),
        Map.of("sku", "NCH-001", "title", "Noise Cancelling Headphones", "description", "Over-ear wireless headphones with hybrid ANC, 40h battery and multipoint pairing.", "price", 199.00, "category", "audio", "tags", List.of("wireless", "bluetooth", "anc", "over-ear"), "attributes", Map.of("battery_hours", 40, "anc", "hybrid", "weight_g", 254), "variants", List.of(Map.of("sku", "NCH-001-BLK", "color", "black", "stock", 18)), "active", true),
        Map.of("sku", "SPK-001", "title", "Studio Monitor Speakers", "description", "Pair of 5-inch powered studio monitors with flat frequency response.", "price", 249.00, "category", "audio", "tags", List.of("studio", "wired", "speakers"), "attributes", Map.of("driver_inch", 5, "power_w", 80, "pair", true), "variants", List.of(Map.of("sku", "SPK-001-BLK", "color", "black", "stock", 12)), "active", true),
        Map.of("sku", "MIC-001", "title", "USB Desk Microphone", "description", "Cardioid condenser microphone with mute button and zero-latency monitoring.", "price", 59.00, "category", "audio", "tags", List.of("usb", "podcast", "microphone"), "attributes", Map.of("polar_pattern", "cardioid", "sample_rate_khz", 48, "connection", "USB-C"), "variants", List.of(Map.of("sku", "MIC-001-BLK", "color", "black", "stock", 26)), "active", true),
        Map.of("sku", "BSP-001", "title", "Portable Bluetooth Speaker", "description", "IPX7 waterproof Bluetooth speaker with 20h playtime and stereo pairing.", "price", 69.00, "category", "audio", "tags", List.of("wireless", "bluetooth", "portable", "outdoor"), "attributes", Map.of("battery_hours", 20, "rating", "IPX7", "output_w", 12), "variants", List.of(Map.of("sku", "BSP-001-BLU", "color", "blue", "stock", 40), Map.of("sku", "BSP-001-RED", "color", "red", "stock", 18)), "active", true),
        Map.of("sku", "EAR-002", "title", "Wired Earbuds Basic", "description", "Lightweight 3.5mm earbuds with in-line microphone — the budget pick.", "price", 9.99, "category", "audio", "tags", List.of("wired", "budget", "in-ear"), "attributes", Map.of("jack", "3.5mm", "cable_length_m", 1.2, "microphone", true), "variants", List.of(Map.of("sku", "EAR-002-WHT", "color", "white", "stock", 200)), "active", true),
        Map.of("sku", "CBL-001", "title", "USB-C to USB-C Cable 2m", "description", "Braided 100W USB-C cable rated for 10Gbps data and 4K video.", "price", 14.99, "category", "cables", "tags", List.of("usb-c", "braided", "charging"), "attributes", Map.of("length_m", 2.0, "power_w", 100, "data_gbps", 10), "variants", List.of(Map.of("sku", "CBL-001-BLK", "color", "black", "stock", 150)), "active", true),
        Map.of("sku", "CBL-002", "title", "HDMI 2.1 Ultra Cable", "description", "48Gbps HDMI 2.1 cable for 4K 120Hz and 8K displays, 1.5m.", "price", 24.50, "category", "cables", "tags", List.of("hdmi", "8k", "gaming"), "attributes", Map.of("length_m", 1.5, "bandwidth_gbps", 48, "version", "2.1"), "variants", List.of(Map.of("sku", "CBL-002-BLK", "color", "black", "stock", 90)), "active", true),
        Map.of("sku", "CBL-003", "title", "Thunderbolt 4 Cable 0.8m", "description", "Certified Thunderbolt 4 cable: 40Gbps, dual 4K, 100W power delivery.", "price", 39.00, "category", "cables", "tags", List.of("thunderbolt", "usb-c", "40gbps"), "attributes", Map.of("length_m", 0.8, "speed_gbps", 40, "power_w", 100), "variants", List.of(Map.of("sku", "CBL-003-BLK", "color", "black", "stock", 70)), "active", true),
        Map.of("sku", "CBL-004", "title", "Ethernet Cat6 Cable 5m", "description", "Snagless Cat6 UTP patch cable for stable gigabit networking.", "price", 16.75, "category", "cables", "tags", List.of("network", "lan", "cat6"), "attributes", Map.of("length_m", 5.0, "category", "Cat6", "speed_mbps", 1000), "variants", List.of(Map.of("sku", "CBL-004-BLU", "color", "blue", "stock", 110)), "active", true),
        Map.of("sku", "CBL-005", "title", "DisplayPort Cable 4K", "description", "DisplayPort 1.4 cable supporting 4K at 144Hz, 2m.", "price", 21.00, "category", "cables", "tags", List.of("displayport", "4k", "gaming"), "attributes", Map.of("length_m", 2.0, "version", "1.4", "refresh_hz", 144), "variants", List.of(Map.of("sku", "CBL-005-BLK", "color", "black", "stock", 85)), "active", true),
        Map.of("sku", "CBL-006", "title", "Braided Lightning Cable", "description", "1m MFi certified Lightning cable with double-braided nylon jacket.", "price", 19.99, "category", "cables", "tags", List.of("lightning", "braided", "charging"), "attributes", Map.of("length_m", 1.0, "certification", "MFi", "colour", "grey"), "variants", List.of(Map.of("sku", "CBL-006-GRY", "color", "grey", "stock", 95)), "active", true),
        Map.of("sku", "LMP-001", "title", "Desk Lamp LED", "description", "Dimmable LED desk lamp with 5 brightness levels and adjustable colour temperature.", "price", 42.00, "category", "office", "tags", List.of("lighting", "desk", "led"), "attributes", Map.of("brightness_levels", 5, "colour_temperature", "2700-6500K", "power_w", 9), "variants", List.of(Map.of("sku", "LMP-001-BLK", "color", "black", "stock", 48), Map.of("sku", "LMP-001-WHT", "color", "white", "stock", 30)), "active", true),
        Map.of("sku", "STN-001", "title", "Adjustable Monitor Stand", "description", "Steel monitor riser with cable tray and two height settings.", "price", 55.00, "category", "office", "tags", List.of("desk", "ergonomics", "monitor"), "attributes", Map.of("material", "steel", "height_mm", 100, "max_weight_kg", 12), "variants", List.of(Map.of("sku", "STN-001-BLK", "color", "black", "stock", 34)), "active", true),
        Map.of("sku", "CHR-001", "title", "Ergonomic Office Chair", "description", "Breathable mesh chair with adjustable lumbar support and 4D armrests.", "price", 329.00, "category", "office", "tags", List.of("chair", "ergonomics", "mesh"), "attributes", Map.of("material", "mesh", "armrests", "4D", "warranty_years", 5), "variants", List.of(Map.of("sku", "CHR-001-BLK", "color", "black", "stock", 14)), "active", true),
        Map.of("sku", "DSK-001", "title", "Standing Desk Converter", "description", "Gas-spring sit-stand converter that fits two 27-inch monitors.", "price", 189.00, "category", "office", "tags", List.of("desk", "standing", "ergonomics"), "attributes", Map.of("lift_kg", 15, "surface_cm", "100x60", "mechanism", "gas spring"), "variants", List.of(Map.of("sku", "DSK-001-BLK", "color", "black", "stock", 20)), "active", true),
        Map.of("sku", "NBT-001", "title", "Notebook Set (3 Pack)", "description", "A5 dotted notebooks with 120gsm paper and lay-flat binding.", "price", 12.50, "category", "office", "tags", List.of("paper", "notes", "stationery"), "attributes", Map.of("size", "A5", "pages", 160, "pack", 3), "variants", List.of(Map.of("sku", "NBT-001-MIX", "color", "assorted", "stock", 180)), "active", true),
        Map.of("sku", "ORG-001", "title", "Desk Organizer Bamboo", "description", "Bamboo desk tidy with five compartments and a phone slot.", "price", 27.50, "category", "office", "tags", List.of("desk", "storage", "eco"), "attributes", Map.of("material", "bamboo", "compartments", 5), "variants", List.of(Map.of("sku", "ORG-001-NAT", "color", "natural", "stock", 65)), "active", true),
        Map.of("sku", "CHG-001", "title", "Wireless Charging Pad", "description", "15W Qi wireless charger with a non-slip surface — currently discontinued.", "price", 35.00, "category", "office", "tags", List.of("wireless", "charging", "qi"), "attributes", Map.of("output_w", 15, "standard", "Qi", "surface", "silicone"), "variants", List.of(Map.of("sku", "CHG-001-BLK", "color", "black", "stock", 0)), "active", false)
    );

    private static final List<List<Map<String, Object>>> ORDER_ITEM_GROUPS = List.of(
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "MK-001", "qty", 1), Map.of("sku", "LMP-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 2), Map.of("sku", "MK-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "MK-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "EAR-001", "qty", 1), Map.of("sku", "CBL-003", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "LMP-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 3), Map.of("sku", "ORG-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "HUB-001", "qty", 1), Map.of("sku", "CBL-003", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 2), Map.of("sku", "MIC-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "HUB-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "EAR-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "LMP-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 2), Map.of("sku", "MK-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "EAR-001", "qty", 1), Map.of("sku", "CBL-003", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "LMP-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 3), Map.of("sku", "ORG-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "HUB-001", "qty", 1), Map.of("sku", "CBL-003", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 2), Map.of("sku", "MIC-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "HUB-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "EAR-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "LMP-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 2), Map.of("sku", "MK-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "EAR-001", "qty", 1), Map.of("sku", "CBL-003", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "LMP-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 3), Map.of("sku", "ORG-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "HUB-001", "qty", 1), Map.of("sku", "CBL-003", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 2), Map.of("sku", "MIC-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "HUB-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "EAR-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "LMP-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 2), Map.of("sku", "MK-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "EAR-001", "qty", 1), Map.of("sku", "CBL-003", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "LMP-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 3), Map.of("sku", "ORG-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "HUB-001", "qty", 1), Map.of("sku", "CBL-003", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 2), Map.of("sku", "MIC-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "HUB-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "EAR-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "LMP-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 2), Map.of("sku", "MK-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "EAR-001", "qty", 1), Map.of("sku", "CBL-003", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "LMP-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 3), Map.of("sku", "ORG-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "HUB-001", "qty", 1), Map.of("sku", "CBL-003", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 2), Map.of("sku", "MIC-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "HUB-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "EAR-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 1), Map.of("sku", "LMP-001", "qty", 1)),
        List.of(Map.of("sku", "WM-001", "qty", 2), Map.of("sku", "MK-001", "qty", 1))
    );

    private static final List<String> ORDER_STATUSES = List.of("PENDING", "PROCESSING", "SHIPPED");

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Starting seed data initialization...");

        seedUsers();
        seedProducts();
        seedOrders();
        applySnapshotDemo();
        reindexSearch();

        log.info("Seed data initialization completed!");
    }

    private void seedUsers() {
        log.info("Seeding users...");
        userRepository.deleteAll();

        // 8 customers with first name as password
        for (int i = 0; i < USERS.size(); i++) {
            Map<String, Object> u = USERS.get(i);
            String name = (String) u.get("name");
            String email = (String) u.get("email");
            String firstName = name.split(" ")[0].toLowerCase();
            
            User user = new User();
            user.setName(name);
            user.setEmail(email);
            // AuthService verifies with BCryptPasswordEncoder.matches(), so a plaintext
            // value here would make every seeded account unable to log in. The demo
            // password stays the first name — only its stored form becomes a bcrypt hash.
            user.setPasswordHash(passwordEncoder.encode(firstName));
            user.setRole("CUSTOMER");
            user.setCreatedAt(DateUtils.utcNow());
            userRepository.save(user);
        }

        // Admin user
        User admin = new User();
        admin.setName("Admin");
        admin.setEmail("admin@example.com");
        admin.setPasswordHash(passwordEncoder.encode("admin"));
        admin.setRole("ADMIN");
        admin.setCreatedAt(DateUtils.utcNow());
        userRepository.save(admin);

        log.info("Users seeded: {} customers + 1 admin", USERS.size());
    }

    private void seedProducts() {
        log.info("Seeding products...");
        // Reset only the demo SKUs. deleteAll() here would also destroy the ~3000 rows written
        // by the catalog seeder (products/seed/CatalogSeedRunner), making `seed` and
        // `catalog-seed` order-dependent and silently emptying the storefront. The two
        // namespaces never overlap: demo SKUs are 3-digit (WM-001), generated SKUs are 4-digit
        // (KIT-1001).
        List<String> demoSkus = PRODUCTS.stream()
            .map(p -> (String) p.get("sku"))
            .collect(Collectors.toList());
        long removed = productRepository.deleteBySkuIn(demoSkus);
        log.info("Removed {} existing demo product(s); {} other product(s) preserved",
            removed, productRepository.count());

        OffsetDateTime now = DateUtils.utcNow();
        for (int i = 0; i < PRODUCTS.size(); i++) {
            Map<String, Object> p = PRODUCTS.get(i);
            ProductDocument doc = new ProductDocument();
            doc.setSku((String) p.get("sku"));
            doc.setTitle((String) p.get("title"));
            doc.setDescription((String) p.get("description"));
            doc.setPrice(((Number) p.get("price")).doubleValue());
            doc.setCategory((String) p.get("category"));
            doc.setTags((List<String>) p.get("tags"));
            doc.setAttributes((Map<String, Object>) p.get("attributes"));
            
            List<Map<String, Object>> variantsRaw = (List<Map<String, Object>>) p.get("variants");
            List<ProductDocument.Variant> variants = variantsRaw.stream().map(v -> {
                ProductDocument.Variant var = new ProductDocument.Variant();
                var.setSku((String) v.get("sku"));
                var.setColor((String) v.get("color"));
                var.setStock(((Number) v.get("stock")).intValue());
                return var;
            }).collect(Collectors.toList());
            doc.setVariants(variants);
            doc.setActive((Boolean) p.get("active"));
            // Product artwork lives in the frontend bundle (/products/<SKU>.svg), not on
            // the backend, so it is referenced by URL. Without this the field stayed null
            // and every card rendered the placeholder instead of an image.
            String sku = (String) p.get("sku");
            doc.setImageUrl(SKU_WITHOUT_ARTWORK.contains(sku)
                ? DEFAULT_ARTWORK_URL
                : "/products/" + sku + ".svg");
            doc.setCreatedAt(now.minusDays(90 - i));
            doc.setUpdatedAt(doc.getCreatedAt());
            productRepository.save(doc);
        }

        log.info("Products seeded: {}", PRODUCTS.size());
    }

    private void seedOrders() {
        log.info("Seeding orders...");
        orderRepository.deleteAll();

        // Build product lookup by SKU
        Map<String, ProductDocument> productsBySku = productRepository.findAll().stream()
            .collect(Collectors.toMap(ProductDocument::getSku, p -> p));

        List<User> users = userRepository.findAll();

        OffsetDateTime baseDate = DateUtils.utcNow();
        int orderCount = 0;

        for (int i = 0; i < ORDER_ITEM_GROUPS.size(); i++) {
            List<Map<String, Object>> items = ORDER_ITEM_GROUPS.get(i);
            
            // Calculate date
            int daysBack = ageDays(i);
            OffsetDateTime orderDate = baseDate.minusDays(daysBack)
                .withHour((i * 13) % 24)
                .withMinute((i * 17) % 60);
            orderDate = orderDate.withSecond(0).withNano(0);

            // Select user (cycle through 8 customers)
            int userIndex = i % 8;
            User user = users.get(userIndex);
            
            // Select status
            String status = ORDER_STATUSES.get(i % ORDER_STATUSES.size());

            // Create order
            Order order = new Order();
            // order_number is NOT NULL, but the final ORD-%06d value depends on the
            // generated ID — which only exists after this first INSERT. Assign a
            // provisional value first, using the same strategy as OrderService.createOrder.
            // The final ORD-%06d number is assigned and persisted below, after the insert.
            order.setOrderNumber("TMP-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
            order.setUser(user);
            order.setOrderDate(orderDate);
            order.setStatus(status);
            order.setCreatedAt(orderDate);
            order.setUpdatedAt(orderDate);

            // Create order items
            BigDecimal total = BigDecimal.ZERO;
            List<OrderItem> orderItems = new ArrayList<>();

            for (Map<String, Object> item : items) {
                String sku = (String) item.get("sku");
                int qty = ((Number) item.get("qty")).intValue();
                ProductDocument product = productsBySku.get(sku);
                
                if (product == null) {
                    log.warn("Product not found for SKU: {}", sku);
                    continue;
                }

                BigDecimal unitPrice = MoneyUtils.toMoney(product.getPrice());
                total = total.add(unitPrice.multiply(BigDecimal.valueOf(qty)));

                OrderItem orderItem = new OrderItem();
                orderItem.setOrder(null); // Will be set after order is saved
                orderItem.setProductId(product.getId());
                orderItem.setTitle(product.getTitle());
                orderItem.setQuantity(qty);
                orderItem.setUnitPrice(unitPrice);
                orderItems.add(orderItem);
            }

            order.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));

            // Attach each item to the owning side of the cascade. Order.items carries
            // CascadeType.ALL, so items must be reachable from that collection for the
            // cascade to persist them — setting only OrderItem.order does not cascade,
            // and would leave order_items empty.
            for (OrderItem item : orderItems) {
                item.setOrder(order);
                order.getItems().add(item);
            }
            order = orderRepository.save(order);

            orderCount++;
            
            // Update order number — the generated ID exists after the insert above
            order.setOrderNumber(String.format("ORD-%06d", order.getId()));
            orderRepository.save(order);
        }

        log.info("Orders seeded: {}", orderCount);
    }

    private int ageDays(int index) {
        if (index <= 4) {
            return 62 + index * 6;
        }
        if (index <= 11) {
            return index - 5;
        }
        return 7 + ((index * 7) % 54);
    }

    private void applySnapshotDemo() {
        log.info("Applying snapshot demo: renaming Wireless Mouse to Wireless Mouse Pro");
        Optional<ProductDocument> productOpt = productRepository.findBySku("WM-001");
        if (productOpt.isPresent()) {
            ProductDocument product = productOpt.get();
            product.setTitle("Wireless Mouse Pro");
            product.setPrice(60.00);
            product.setDescription("Upgraded 2.4GHz wireless mouse with silent clicks, 3200 DPI sensor and 18-month battery life.");
            product.setUpdatedAt(DateUtils.utcNow());
            productRepository.save(product);
        }
    }

    private void reindexSearch() {
        log.info("Reindexing search...");
        try {
            // Full reindex: drop the index first so documents for orders that no longer
            // exist in PostgreSQL (their IDs advance on every re-run) cannot accumulate.
            searchRepository.recreateIndex();
            List<Order> orders = orderRepository.findAll();
            List<ElasticsearchOrderDocument> documents = new ArrayList<>();
            for (Order order : orders) {
                // findAll() leaves items/user lazy. This runner runs outside a transaction,
                // so reload each order with the fetch-join query before mapping it.
                orderRepository.findByIdWithItemsAndUser(order.getId())
                    .ifPresent(full -> documents.add(buildSearchDocument(full)));
            }
            searchRepository.bulkIndexOrders(documents);
            log.info("Search reindex completed for {} orders", documents.size());
        } catch (Exception ex) {
            log.warn("Search reindex failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Maps an Order to the Elasticsearch document. Mirrors the mapping used by the live
     * RabbitMQ sync path (OrderSyncListener) so seeded orders and runtime-synced orders
     * produce identical documents.
     */
    private ElasticsearchOrderDocument buildSearchDocument(Order order) {
        ElasticsearchOrderDocument doc = new ElasticsearchOrderDocument();
        doc.setOrderId((long) order.getId());
        doc.setOrderNumber(order.getOrderNumber());
        doc.setStatus(order.getStatus());
        doc.setOrderDate(order.getOrderDate());
        doc.setUpdatedAt(order.getUpdatedAt());
        doc.setTotalAmount(order.getTotalAmount().doubleValue());

        ElasticsearchOrderDocument.Customer customer = new ElasticsearchOrderDocument.Customer();
        customer.setId((long) order.getUser().getId());
        customer.setName(order.getUser().getName());
        customer.setEmail(order.getUser().getEmail());
        doc.setCustomer(customer);

        doc.setItems(order.getItems().stream().map(item -> {
            ElasticsearchOrderDocument.Item esItem = new ElasticsearchOrderDocument.Item();
            esItem.setProductId(item.getProductId());
            esItem.setTitle(item.getTitle());
            esItem.setQuantity(item.getQuantity());
            esItem.setUnitPrice(item.getUnitPrice().doubleValue());
            esItem.setLineTotal(item.getLineTotal().doubleValue());
            return esItem;
        }).collect(Collectors.toList()));

        return doc;
    }
}