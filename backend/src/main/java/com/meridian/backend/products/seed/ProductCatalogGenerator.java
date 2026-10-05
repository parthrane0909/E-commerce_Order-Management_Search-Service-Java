package com.meridian.backend.products.seed;

import com.meridian.backend.products.document.ProductDocument;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic generator for the bulk product catalog.
 *
 * <p>Design goals (see the catalog-seed brief):
 * <ul>
 *   <li><b>Reproducible</b> — a single fixed {@link #RANDOM_SEED} drives every choice, so the
 *       same target count always yields byte-identical SKU/title/price/attribute combinations.</li>
 *   <li><b>Genuinely varied</b> — 31 categories spanning electronics, kitchen, apparel, fitness,
 *       pets, automotive … each with its own product names, qualifiers, attribute schema, tags
 *       and price band. Nothing is a "Product 0001" template.</li>
 *   <li><b>Collision free</b> — SKUs live in a dedicated 4-digit namespace ({@code XXX-1001+})
 *       that never overlaps the 3-digit demo SKUs, and any SKU already present in MongoDB is
 *       skipped deterministically instead of being overwritten.</li>
 *   <li><b>Schema preserving</b> — output is plain {@link ProductDocument}: the same fields,
 *       field names and serialization the application already uses.</li>
 * </ul>
 *
 * <p>This class has no Spring dependency so it can be unit tested in isolation.
 */
public final class ProductCatalogGenerator {

    /** Fixed seed — changing it changes every generated product, so treat it as an API. */
    public static final long RANDOM_SEED = 20261005L;

    /**
     * Anchor for all generated timestamps. Fixed (rather than {@code now()}) so repeated runs
     * produce identical documents; spans 2024-11 … 2026-10, overlapping the demo products so
     * the default {@code newest} ordering mixes both sets instead of front-loading one.
     */
    public static final OffsetDateTime CATALOG_EPOCH = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    /** Generated SKUs are 4-digit; demo SKUs are 3-digit — this regex identifies our own rows. */
    public static final String GENERATED_SKU_REGEX = "^[A-Z]{3}-[0-9]{4}$";

    /** First SKU number issued in each category namespace. */
    public static final int FIRST_SKU_NUMBER = 1001;

    /** One entry per category: name space + the content pools that make it look real. */
    public record CategorySpec(
        String key,
        String skuPrefix,
        String[] bases,
        String[] qualifiers,
        String[][] attributes,
        String[] tags,
        double priceMin,
        double priceMax,
        boolean variantHeavy) {
    }

    private static final String[] BRANDS = {
        "Northwind", "Aurora", "Kestrel", "Vantage", "Lumen", "Basalt", "Harbor", "Solace",
        "Cobalt", "Marlow", "Ridgeline", "Ferro", "Halcyon", "Juniper", "Orbit", "Quarry"
    };

    private static final String[] COLOURS = {
        "Black", "White", "Charcoal", "Navy", "Sand", "Olive", "Coral", "Slate",
        "Ivory", "Graphite", "Rust", "Sage"
    };

    private static final String[] COLOUR_CODES = {
        "BLK", "WHT", "CHR", "NVY", "SND", "OLV", "CRL", "SLT",
        "IVY", "GRF", "RST", "SGE"
    };

    /**
     * 31 categories. The first four ({@code peripherals, audio, cables, office}) are the
     * categories the application already ships with and are kept verbatim so existing
     * bookmarks, facet links and filter URLs keep resolving.
     */
    public static final List<CategorySpec> CATEGORIES = List.of(
        new CategorySpec("peripherals", "PRP",
            new String[]{"Wireless Optical Mouse", "Ergonomic Vertical Mouse", "Mechanical Gaming Keyboard",
                "Low-Profile Wireless Keyboard", "USB-C Docking Station", "Graphics Drawing Tablet",
                "Webcam with Ring Light", "Presenter Remote", "Gaming Mousepad", "Laptop Cooling Pad",
                "Trackball Mouse", "Compact Bluetooth Keyboard", "Numeric Keypad", "Monitor Light Bar"},
            new String[]{"Black", "Graphite", "Silent Edition", "RGB", "1600 DPI", "TKL", "Full Size", "Portable"},
            new String[][]{{"material", "ABS|Aluminium|Rubber"}, {"connectivity", "2.4GHz|Bluetooth 5.0|USB-C|Wired"},
                {"colour", "Black|White|Grey"}, {"warranty_months", "6|12|24"}},
            new String[]{"wireless", "ergonomic", "office", "gaming", "rgb", "usb-c", "desk"},
            8, 130, true),

        new CategorySpec("audio", "AUD",
            new String[]{"True Wireless Earbuds", "Over-Ear Wireless Headphones", "Noise Cancelling Headset",
                "Portable Bluetooth Speaker", "Studio Monitor Speakers", "USB Desk Microphone",
                "Dynamic Podcast Microphone", "Wired Earphones", "Karaoke Microphone", "Soundbar for TV",
                "Turntable with Phono Preamp", "In-Ear Monitors"},
            new String[]{"Black", "White", "Midnight Blue", "40h Battery", "IPX7", "Hybrid ANC", "Studio Grade"},
            new String[][]{{"connectivity", "Bluetooth 5.3|3.5mm|USB-C|XLR"}, {"battery_hours", "8|24|40"},
                {"driver_mm", "10|40|50"}, {"rating", "IPX5|IPX7|None"}},
            new String[]{"wireless", "bluetooth", "audio", "studio", "portable", "anc", "in-ear"},
            9, 320, true),

        new CategorySpec("cables", "CBL",
            new String[]{"USB-C to USB-C Cable", "USB-A to USB-C Cable", "HDMI 2.1 Cable", "DisplayPort Cable",
                "Ethernet Patch Cable", "Thunderbolt 4 Cable", "Lightning Cable", "3.5mm Audio Cable",
                "Coaxial TV Cable", "Fibre Optic Patch Cable", "Serial DB9 Cable", "SATA Data Cable"},
            new String[]{"1m", "2m", "3m", "5m", "Braided", "100W", "48Gbps", "Snagless"},
            new String[][]{{"length_m", "1|2|3|5"}, {"bandwidth_gbps", "10|20|40|48"},
                {"connector", "USB-C|HDMI|DisplayPort|RJ45"}, {"braided", "true|false"}},
            new String[]{"usb-c", "charging", "braided", "hdmi", "network", "8k"},
            5, 45, false),

        new CategorySpec("office", "OFC",
            new String[]{"Ergonomic Office Chair", "Standing Desk Converter", "Monitor Stand Riser",
                "Desk Organizer", "Two-Drawer Filing Cabinet", "Magnetic Whiteboard", "Under-Desk Footrest",
                "Aluminium Laptop Riser", "Leather Desk Mat", "Wireless Document Tray", "Cable Management Tray",
                "Height-Adjustable Stool"},
            new String[]{"Mesh", "Bamboo", "Powder-Coated Steel", "Black", "Height Adjustable", "Oak Effect"},
            new String[][]{{"material", "Mesh|Steel|Bamboo|MDF"}, {"warranty_years", "1|2|5"},
                {"max_load_kg", "10|15|120"}, {"colour", "Black|White|Walnut"}},
            new String[]{"desk", "ergonomics", "office", "storage", "workspace"},
            12, 420, false),

        new CategorySpec("electronics", "ELC",
            new String[]{"Power Bank 20000mAh", "Portable Pico Projector", "Bluetooth Item Tracker",
                "Digital Multimeter", "Soldering Iron Kit", "Breadboard Prototyping Kit", "Walkie Talkie Pair",
                "Digital Alarm Clock", "RFID Card Reader", "Universal Battery Charger", "Pocket Fan Heater",
                "Infrared Thermometer"},
            new String[]{"Compact", "Fast Charge", "Rechargeable", "Dual Pack", "Pro", "Pocket Size"},
            new String[][]{{"battery_mah", "5000|10000|20000"}, {"input_voltage", "110-240V"},
                {"warranty_months", "6|12|24"}, {"colour", "Black|White|Blue"}},
            new String[]{"gadgets", "portable", "rechargeable", "diy", "travel"},
            8, 260, false),

        new CategorySpec("computer-accessories", "CMP",
            new String[]{"External DVD Writer", "Keyboard Wrist Rest", "Webcam Privacy Cover Set",
                "USB Macro Pad", "All-in-One Card Reader", "Desktop Microphone Boom Arm", "Laptop Sleeve 15-inch",
                "Monitor Dust Cover", "Thermal Paste Applicator", "Anti-Fatigue Standing Mat",
                "Cable Tie Organizer Pack", "Screen Cleaning Kit"},
            new String[]{"Universal", "Slim", "Magnetic", "Cushioned", "Aluminium", "15-inch"},
            new String[][]{{"material", "ABS|Aluminium|Neoprene"}, {"compatibility", "Windows|macOS|Universal"},
                {"pack_size", "1|3|6"}},
            new String[]{"accessories", "desk", "laptop", "office", "ergonomic"},
            6, 120, false),

        new CategorySpec("mobile-accessories", "MOB",
            new String[]{"USB-C GaN Charger", "Wireless Charging Stand", "Magnetic Phone Grip",
                "Slim Power Bank", "Universal Car Vent Mount", "Tempered Glass Screen Protector",
                "Phone Wallet Case", "Wrist Lanyard Strap", "Adjustable Tablet Stand", "MagSafe Car Charger",
                "Cable Organizer Pouch", "Selfie Ring Light"},
            new String[]{"65W", "100W", "Magnetic Free", "Shockproof", "Matte Clear", "Fast Charge"},
            new String[][]{{"output_w", "20|33|65|100"}, {"ports", "1|2|3"},
                {"standard", "Qi2|MagSafe|USB-PD"}, {"colour", "Black|Clear|Sand"}},
            new String[]{"usb-c", "charging", "mobile", "travel", "wireless"},
            5, 95, true),

        new CategorySpec("networking", "NTK",
            new String[]{"Wi-Fi 6 Router", "Gigabit Network Switch", "Mesh Wi-Fi Node", "Cat6 Patch Panel",
                "Powerline Adapter Twin Pack", "Network Cable Tester", "Wi-Fi Range Extender",
                "Rack-Mount Cooling Fan", "PoE Injector", "Fibre Media Converter", "Outdoor Access Point",
                "Network Crimping Tool"},
            new String[]{"Dual Band", "8-Port", "AX1800", "Wall Plug", "Managed", "Outdoor"},
            new String[][]{{"ports", "4|8|16"}, {"standard", "Wi-Fi 6|Wi-Fi 6E|Gigabit"},
                {"throughput_mbps", "1000|2500|5400"}, {"poe", "true|false"}},
            new String[]{"network", "wifi", "gigabit", "router", "lan"},
            18, 380, false),

        new CategorySpec("storage", "STG",
            new String[]{"Portable SSD 1TB", "USB Flash Drive 128GB", "External Hard Drive 2TB",
                "NVMe SSD Enclosure", "MicroSD Card 256GB", "NAS Drive 4TB", "CFexpress Card Reader",
                "USB Floppy Drive", "Rugged Drive Case", "Memory Card Wallet"},
            new String[]{"USB 3.2", "Gen 2", "Rugged", "Compact", "High Speed", "Bulk Pack"},
            new String[][]{{"capacity_gb", "64|256|1000|2000"}, {"interface", "USB 3.2|NVMe|SATA"},
                {"read_mbps", "540|1050|3500"}, {"colour", "Black|Silver|Blue"}},
            new String[]{"storage", "ssd", "backup", "usb", "portable"},
            12, 320, false),

        new CategorySpec("gaming", "GMG",
            new String[]{"Wireless Game Controller", "RGB Gaming Headset", "Console Charging Dock",
                "Arcade Fight Stick", "Racing Wheel Stand", "Stream Deck Pad", "VR Controller Grips",
                "Controller Thumbstick Caps", "Gaming Notebook", "LED Keyboard Wrist Bar"},
            new String[]{"PC", "PlayStation", "Xbox", "RGB", "Hall Effect", "Esports"},
            new String[][]{{"connectivity", "2.4GHz|Bluetooth|Wired"}, {"rgb", "true|false"},
                {"platform", "PC|PS5|Xbox"}, {"battery_hours", "10|20|30"}},
            new String[]{"gaming", "rgb", "controller", "esports", "accessories"},
            9, 360, true),

        new CategorySpec("monitors", "MNT",
            new String[]{"27-inch 4K Monitor", "24-inch IPS Monitor", "Ultrawide 34-inch Monitor",
                "Portable USB-C Monitor", "165Hz Gaming Monitor", "Curved 32-inch Monitor",
                "Dual Monitor Arm", "Privacy Screen Filter"},
            new String[]{"IPS", "VA", "HDR400", "Frameless", "144Hz", "165Hz"},
            new String[][]{{"size_inch", "24|27|32|34"}, {"refresh_hz", "60|144|165"},
                {"panel", "IPS|VA|OLED"}, {"ports", "HDMI|DP|USB-C"}},
            new String[]{"monitor", "display", "4k", "gaming", "workstation"},
            110, 720, false),

        new CategorySpec("cameras", "CMR",
            new String[]{"Mirrorless Camera Body", "50mm Prime Lens", "Aluminium Camera Tripod",
                "Waterproof Action Camera", "Dual-Channel Dash Camera", "Indoor Security Camera",
                "4K Streaming Webcam", "Camera Sling Bag", "LED Ring Light", "3-Axis Gimbal Stabiliser",
                "Lens Cleaning Pen", "Memory Card Twin Pack"},
            new String[]{"4K", "60fps", "Wi-Fi", "Night Vision", "Compact", "Weather Sealed"},
            new String[][]{{"sensor_mp", "12|24|48"}, {"focal_length", "35mm|50mm|N/A"},
                {"stabilisation", "3-axis|Optical|None"}, {"resolution", "1080p|2.7K|4K"}},
            new String[]{"camera", "video", "photography", "4k", "portable"},
            25, 950, false),

        new CategorySpec("stationery", "STY",
            new String[]{"A4 Spiral Notebook", "Gel Pen Set", "Desktop Stapler", "Sticky Note Bundle",
                "Mechanical Pencil", "Highlighter Set", "Expanding Document Folder", "A4 Ring Binder",
                "Whiteboard Marker Set", "Geometry Set", "Index Card Box", "Wax Sealing Kit"},
            new String[]{"Black Ink", "Assorted", "A5", "A4", "Dotted", "Refillable"},
            new String[][]{{"pages", "80|160|240"}, {"pack", "1|3|10"}, {"paper_gsm", "80|100|120"}},
            new String[]{"stationery", "notes", "office", "paper", "school"},
            3, 38, false),

        new CategorySpec("kitchen", "KIT",
            new String[]{"Stainless Steel Kitchen Tongs", "Ceramic Coffee Mug", "Bamboo Cutting Board",
                "Silicone Spatula Set", "Chef Knife 8-Inch", "Vegetable Peeler", "Mixing Bowl Set",
                "Measuring Cup Set", "Colander Strainer", "Manual Can Opener", "Garlic Rocker Press",
                "Egg Slicer"},
            new String[]{"Stainless Steel", "Non-Stick", "Bamboo", "Silicone", "Ceramic", "Dishwasher Safe"},
            new String[][]{{"material", "Stainless Steel|Silicone|Ceramic|Bamboo"},
                {"dishwasher_safe", "true|false"}, {"pieces", "1|3|5"}},
            new String[]{"kitchen", "cooking", "utensils", "home", "prep"},
            4, 75, false),

        new CategorySpec("kitchenware", "KTW",
            new String[]{"Stoneware Dinner Set", "Glass Storage Jar Set", "Acacia Serving Board",
                "Espresso Cup Set", "Salt and Pepper Mill", "Oil Dispenser Bottle", "Bread Bin",
                "Napkin Ring Set", "Serving Platter", "Tea Infuser Pot"},
            new String[]{"Set of 2", "Set of 4", "Set of 6", "Matte Glaze", "Handmade", "Clear Glass"},
            new String[][]{{"material", "Stoneware|Glass|Acacia"}, {"pieces", "2|4|6"},
                {"capacity_ml", "250|500|1000"}},
            new String[]{"kitchen", "dining", "home", "serveware"},
            8, 130, false),

        new CategorySpec("cookware", "CKW",
            new String[]{"Non-Stick Frying Pan", "Cast Iron Skillet", "Stainless Steel Saucepan",
                "Hard-Anodised Kadai", "Pressure Cooker 5L", "Reversible Grill Pan", "Roasting Tray",
                "Steamer Insert", "Wok with Lid", "Sauté Pan"},
            new String[]{"Induction Ready", "10-Inch", "12-Inch", "5-Litre", "Ceramic Coated", "Pre-Seasoned"},
            new String[][]{{"diameter_inch", "8|10|12"}, {"coating", "Non-Stick|Ceramic|None"},
                {"induction_safe", "true|false"}, {"capacity_l", "2|3|5"}},
            new String[]{"cookware", "kitchen", "induction", "non-stick"},
            15, 210, false),

        new CategorySpec("utensils", "UTL",
            new String[]{"Slotted Turner", "Ladle Serving Spoon", "Balloon Whisk", "Potato Masher",
                "Pizza Cutter", "Garlic Press", "Box Grater", "12-Inch Tongs", "Basting Brush",
                "Corn Stripper"},
            new String[]{"Nylon", "Mirror Polish", "Heat Resistant", "Soft Grip", "Dishwasher Safe"},
            new String[][]{{"material", "Nylon|Stainless Steel|Silicone"},
                {"heat_resistant_c", "180|220|260"}, {"grip", "Soft|Steel|Wood"}},
            new String[]{"utensils", "kitchen", "cooking", "prep"},
            4, 42, false),

        new CategorySpec("home", "HOM",
            new String[]{"Scented Soy Candle", "Ceramic Vase", "Cotton Throw Blanket", "Round Wall Clock",
                "Picture Frame Set", "Woven Storage Basket", "Reed Room Diffuser", "Coir Doormat",
                "Macramé Wall Hanging", "Ceramic Soap Dispenser"},
            new String[]{"Lavender", "Vanilla", "Large", "Medium", "Handwoven", "Matte"},
            new String[][]{{"material", "Soy Wax|Ceramic|Cotton|Coir"}, {"height_cm", "15|25|35"},
                {"scent", "Lavender|Vanilla|Unscented"}},
            new String[]{"home", "decor", "living-room", "gifts"},
            6, 95, false),

        new CategorySpec("cleaning", "CLN",
            new String[]{"Microfibre Cleaning Cloth", "All-Purpose Spray Bottle", "Spin Mop System",
                "Glass Squeegee", "Dish Brush with Holder", "Mesh Laundry Bag", "Extendable Duster",
                "Toilet Brush Set", "Dusting Gloves", "Stain Removal Pen"},
            new String[]{"Pack of 4", "Reusable", "Microfibre", "Extendable", "Refillable", "Lint Free"},
            new String[][]{{"material", "Microfibre|ABS|Stainless Steel"}, {"pieces", "1|2|4"},
                {"reusable", "true|false"}},
            new String[]{"cleaning", "home", "household", "reusable"},
            4, 65, false),

        new CategorySpec("lighting", "LGT",
            new String[]{"LED Desk Lamp", "Colour Smart Bulb", "LED Strip 5m", "Arched Floor Lamp",
                "Wall Reading Sensor Light", "Plug-In Night Light", "Flush Ceiling Light",
                "Under-Cabinet Light Bar", "Solar Garden Spike", "Portable Camping Lantern"},
            new String[]{"Dimmable", "Tunable White", "Colour Changing", "Warm White", "Battery Powered"},
            new String[][]{{"wattage", "6|9|15"}, {"colour_temp", "2700K|4000K|6500K"},
                {"smart", "true|false"}, {"bulbs", "1|2|4"}},
            new String[]{"lighting", "led", "home", "desk", "smart"},
            8, 175, false),

        new CategorySpec("furniture", "FUR",
            new String[]{"5-Tier Bookshelf", "Nesting Side Tables", "Storage Ottoman",
                "4-Level Shoe Rack", "Bar Stool Pair", "Oak Coffee Table", "Folding Camping Chair",
                "Wall-Mounted Drop-Leaf Desk", "Bedside Cabinet", "Hallway Console Table"},
            new String[]{"Oak", "Black Steel", "Engineered Wood", "Set of 2", "Flat Pack", "Walnut"},
            new String[][]{{"material", "Oak|Steel|Engineered Wood"}, {"tiers", "3|4|5"},
                {"assembly_required", "true|false"}},
            new String[]{"furniture", "home", "storage", "living-room"},
            30, 540, false),

        new CategorySpec("clothing", "CLO",
            new String[]{"Cotton Casual Hoodie", "Oxford Cotton Shirt", "Slim Fit Chinos",
                "Crew Neck T-Shirt", "Zip-Up Track Jacket", "Piqué Polo Shirt", "Knit Beanie",
                "Quick-Dry Sports Shorts", "Flannel Check Shirt", "Padded Gilet"},
            new String[]{"Black", "Navy", "Olive", "Size M", "Size L", "Heavyweight", "Brushed Fleece"},
            new String[][]{{"material", "100% Cotton|Polyester|Fleece"}, {"size", "S|M|L|XL"},
                {"care", "Machine Wash|Hand Wash"}, {"fit", "Regular|Slim|Relaxed"}},
            new String[]{"clothing", "casual", "cotton", "everyday"},
            9, 130, true),

        new CategorySpec("footwear", "FWT",
            new String[]{"Running Shoes", "Canvas Sneakers", "Leather Formal Shoes", "Trail Hiking Boots",
                "Slide Sandals", "Memory Foam Slippers", "Court Trainers", "Breathable Mesh Trainers"},
            new String[]{"Size 9", "Size 10", "All White", "Black", "Waterproof", "Lightweight"},
            new String[][]{{"upper", "Mesh|Canvas|Leather"}, {"sole", "EVA|Rubber"},
                {"size", "8|9|10|11"}, {"waterproof", "true|false"}},
            new String[]{"footwear", "sneakers", "outdoor", "everyday"},
            20, 270, true),

        new CategorySpec("bags", "BAG",
            new String[]{"Travel Backpack", "Laptop Briefcase", "40L Duffel Bag", "Crossbody Sling Bag",
                "Gym Sports Bag", "Canvas Tote Bag", "Belt Waist Pack", "Bike Pannier Bag",
                "Camera Backpack", "Kids School Rucksack"},
            new String[]{"24L", "35L", "Water Resistant", "Anti-Theft", "Canvas", "Black"},
            new String[][]{{"capacity_l", "12|24|40"}, {"material", "Polyester|Canvas|Nylon"},
                {"laptop_size", "13|15|17"}},
            new String[]{"bags", "travel", "backpack", "commute"},
            15, 210, true),

        new CategorySpec("travel", "TRV",
            new String[]{"Cabin Trolley Case", "Packing Cube Set", "Memory Foam Neck Pillow",
                "Digital Luggage Scale", "Passport Holder", "Hanging Toiletry Kit",
                "Universal Travel Adapter", "Luggage Strap Set", "Compression Socks Pair",
                "Foldable Water Bottle"},
            new String[]{"Cabin Size", "Hard Shell", "Set of 3", "55cm", "Universal", "Lightweight"},
            new String[][]{{"capacity_l", "35|55|75"}, {"material", "Polycarbonate|Nylon"},
                {"pieces", "1|3|4"}, {"weight_kg", "0.3|2.4|3.1"}},
            new String[]{"travel", "luggage", "holiday", "airport"},
            8, 280, false),

        new CategorySpec("fitness", "FIT",
            new String[]{"Adjustable Dumbbell Pair", "6mm Yoga Mat", "Resistance Band Set",
                "Speed Jump Rope", "Textured Foam Roller", "Cast Iron Kettlebell",
                "Ab Rollout Wheel", "Insulated Water Bottle 1L", "Grip Strength Trainer",
                "Pull-Up Assist Band"},
            new String[]{"10kg", "16kg", "Anti-Slip", "Non-Toxic", "Set of 5", "Programmable"},
            new String[][]{{"weight_kg", "1|4|10|16"}, {"material", "TPE|Cast Steel|Silicone"},
                {"pieces", "1|3|5"}, {"length_cm", "90|120|180"}},
            new String[]{"fitness", "home-workout", "gym", "training"},
            6, 260, false),

        new CategorySpec("personal-care", "PCR",
            new String[]{"Ionic Hair Dryer", "Beard Trimmer", "Electric Toothbrush",
                "Face Cleansing Brush", "Nail Care Kit", "Shaving Foaming Gel", "Heated Massage Comb",
                "Manicure Set", "Curling Wand", "Digital Bathroom Scale"},
            new String[]{"Ionic", "Cordless", "Waterproof", "Travel Size", "Two-Speed", "Ceramic"},
            new String[][]{{"power_w", "600|1200|1800"}, {"battery_min", "40|60|90"},
                {"waterproof", "true|false"}, {"colour", "White|Rose|Black"}},
            new String[]{"personal-care", "grooming", "bathroom", "travel"},
            7, 190, false),

        new CategorySpec("toys", "TYS",
            new String[]{"Wooden Building Blocks", "1000-Piece Jigsaw Puzzle", "Large Plush Bear",
                "Remote Control Car", "Magnetic Tile Set", "Family Board Game", "Outdoor Kite",
                "Arts and Craft Kit", "Shape Sorter Cube", "Stacking Ring Tower"},
            new String[]{"Ages 3+", "Ages 6+", "Ages 8+", "Educational", "Family Size", "Wooden"},
            new String[][]{{"age", "3+|6+|8+"}, {"pieces", "24|100|1000"},
                {"material", "Wood|ABS|Plush"}, {"players", "1|2|4"}},
            new String[]{"toys", "kids", "gifts", "educational"},
            6, 130, false),

        new CategorySpec("outdoor", "OTD",
            new String[]{"2-Person Camping Tent", "Portable Camp Lantern", "Foldable Picnic Table",
                "Comfort Sleeping Bag", "Trekking Pole Pair", "Waterproof Dry Bag", "Camp Kettle Steel",
                "Ground Sheet Tarp", "Head Torch Rechargeable", "Hammock with Straps"},
            new String[]{"Waterproof", "2-Person", "Ultralight", "3000mm", "Rechargeable", "Olive"},
            new String[][]{{"capacity_p", "1|2|4"}, {"weight_kg", "1.5|2.8|4.2"},
                {"waterproof", "true|false"}, {"material", "Ripstop|Polyester|Aluminium"}},
            new String[]{"outdoor", "camping", "hiking", "travel"},
            15, 420, false),

        new CategorySpec("pet-supplies", "PET",
            new String[]{"Stainless Pet Bowl", "Adjustable Dog Leash", "Cat Scratching Post",
                "Orthopaedic Pet Bed", "Interactive Cat Toy", "Window Bird Feeder",
                "Waste Bag Dispenser", "Grooming Brush", "Pet Travel Water Bottle", "Cat Litter Mat"},
            new String[]{"Small", "Medium", "Large", "Non-Slip", "Anti-Spill", "Double Layer"},
            new String[][]{{"material", "Stainless Steel|Plush|Sisal"}, {"size", "S|M|L"},
                {"suitable_for", "Cat|Dog|Bird"}, {"washable", "true|false"}},
            new String[]{"pets", "dogs", "cats", "accessories"},
            5, 135, false),

        new CategorySpec("automotive", "AUT",
            new String[]{"Car Floor Mat Set", "Digital Tyre Pressure Gauge", "Dashboard Phone Holder",
                "Portable Car Vacuum", "Microfibre Drying Towel", "Seat Back Organizer",
                "Portable Jump Starter", "Windscreen Sun Shade", "Interior LED Strip Kit",
                "Boot Cargo Tray"},
            new String[]{"Universal", "All-Weather", "SUV", "Sedan", "600g", "Anti-Slip"},
            new String[][]{{"material", "Rubber|ABS|Microfibre"}, {"pieces", "1|2|4"},
                {"compatible", "Universal|Sedan|SUV"}, {"voltage", "12V|24V"}},
            new String[]{"automotive", "car", "accessories", "cleaning"},
            7, 240, false)
    );

    private ProductCatalogGenerator() {
    }

    /**
     * Generates {@code targetCount} products.
     *
     * @param targetCount   how many products to generate
     * @param reservedSkus  SKUs that already exist in MongoDB — never reused, never overwritten
     * @param imagePool     validated artwork pool
     * @return a fresh list of detached {@link ProductDocument}s ready for bulk insert
     */
    public static List<ProductDocument> generate(int targetCount,
                                                 Set<String> reservedSkus,
                                                 ProductImagePool imagePool) {
        return generate(targetCount, reservedSkus, Set.of(), imagePool);
    }

    /**
     * Generates {@code targetCount} products while steering clear of titles already in the
     * catalog. Reserving the demo products' names keeps the merged catalog free of two rows
     * that read identically — a generated unit simply picks its qualifier variant instead.
     *
     * @param targetCount    how many products to generate
     * @param reservedSkus   SKUs that already exist in MongoDB — never reused, never overwritten
     * @param reservedTitles titles that already exist in MongoDB — never reused
     * @param imagePool      validated artwork pool
     * @return a fresh list of detached {@link ProductDocument}s ready for bulk insert
     */
    public static List<ProductDocument> generate(int targetCount,
                                                 Set<String> reservedSkus,
                                                 Set<String> reservedTitles,
                                                 ProductImagePool imagePool) {
        if (targetCount < 1) {
            throw new IllegalArgumentException("targetCount must be >= 1, was " + targetCount);
        }

        Random rnd = new Random(RANDOM_SEED);
        Set<String> usedSkus = new HashSet<>(reservedSkus);
        Set<String> usedTitles = new HashSet<>(reservedTitles);
        List<ProductDocument> products = new ArrayList<>(targetCount);

        int[] skuCounters = new int[CATEGORIES.size()];
        for (int i = 0; i < CATEGORIES.size(); i++) {
            skuCounters[i] = FIRST_SKU_NUMBER;
        }

        for (int i = 0; i < targetCount; i++) {
            int categoryIndex = i % CATEGORIES.size();
            CategorySpec category = CATEGORIES.get(categoryIndex);

            String sku = nextFreeSku(category.skuPrefix(), skuCounters, categoryIndex, usedSkus);
            Map<String, Object> attributes = buildAttributes(rnd, category);
            String title = buildTitle(rnd, category, usedTitles, sku);
            String description = buildDescription(rnd, category, title, attributes);
            double price = buildPrice(rnd, category);
            List<String> tags = buildTags(rnd, category);
            List<ProductDocument.Variant> variants = buildVariants(rnd, category, sku);
            String imageUrl = imagePool.pick(category.key(), i);

            OffsetDateTime createdAt = CATALOG_EPOCH.minusDays(i % 700).minusMinutes(i);
            OffsetDateTime updatedAt = (i % 3 == 0 && createdAt.isBefore(CATALOG_EPOCH.minusDays(1)))
                ? createdAt.plusDays(1)
                : createdAt;

            ProductDocument doc = new ProductDocument();
            doc.setSku(sku);
            doc.setTitle(title);
            doc.setDescription(description);
            doc.setPrice(price);
            doc.setCategory(category.key());
            doc.setTags(tags);
            doc.setAttributes(attributes);
            doc.setVariants(variants);
            // A steady 4% inactive slice keeps `visibility=inactive` honest without hiding
            // whole categories from the storefront.
            doc.setActive(i % 25 != 0);
            doc.setImageUrl(imageUrl);
            doc.setCreatedAt(createdAt);
            doc.setUpdatedAt(updatedAt);
            products.add(doc);
        }

        return products;
    }

    /** Deterministic SKU allocation that steps around anything already stored. */
    private static String nextFreeSku(String prefix, int[] counters, int categoryIndex, Set<String> usedSkus) {
        String sku;
        do {
            sku = prefix + "-" + counters[categoryIndex];
            counters[categoryIndex]++;
        } while (usedSkus.contains(sku));
        usedSkus.add(sku);
        return sku;
    }

    private static Map<String, Object> buildAttributes(Random rnd, CategorySpec category) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("brand", pick(rnd, BRANDS));
        for (String[] row : category.attributes()) {
            attributes.put(row[0], coerce(pick(rnd, row[1].split("\\|"))));
        }
        if (rnd.nextInt(3) == 0) {
            attributes.put("colour", pick(rnd, COLOURS).toLowerCase());
        }
        return attributes;
    }

    /**
     * Realistic names only: a base product name, optionally narrowed by qualifiers, and the
     * unique SKU as the last resort. Never "Product 0001".
     */
    private static String buildTitle(Random rnd, CategorySpec category, Set<String> usedTitles, String sku) {
        String base = pick(rnd, category.bases());
        String q1 = pick(rnd, category.qualifiers());
        String q2 = pick(rnd, category.qualifiers());
        String[] candidates = {
            base,
            base + " (" + q1 + ")",
            base + " (" + q1 + ", " + q2 + ")",
            base + " " + q1 + " — " + sku
        };
        for (String candidate : candidates) {
            if (usedTitles.add(candidate)) {
                return candidate;
            }
        }
        // SKU-suffixed fallback is unique by construction; record it so the invariant holds.
        String fallback = base + " — " + sku;
        usedTitles.add(fallback);
        return fallback;
    }

    private static String buildDescription(Random rnd,
                                           CategorySpec category,
                                           String title,
                                           Map<String, Object> attributes) {
        String qualifier = pick(rnd, category.qualifiers());
        String features = featurePhrase(rnd, attributes);
        int months = 6 + rnd.nextInt(19);
        return switch (rnd.nextInt(4)) {
            case 0 -> title + " in " + qualifier.toLowerCase() + ". " + features
                + ". Backed by a " + months + "-month warranty and dispatched within one working day.";
            case 1 -> title + " — " + qualifier + ". " + features
                + ", and covered against manufacturing defects for " + months + " months.";
            case 2 -> "A dependable " + category.key().replace('-', ' ')
                + " essential. " + features + ". " + title + " arrives in recyclable packaging with a "
                + months + "-month warranty.";
            default -> title + " (" + qualifier + "). " + features
                + ". In stock and ready to ship.";
        };
    }

    /** Turns two randomly chosen attribute rows into a readable sentence fragment. */
    private static String featurePhrase(Random rnd, Map<String, Object> attributes) {
        List<String> keys = new ArrayList<>(attributes.keySet());
        keys.remove("brand");
        if (keys.isEmpty()) {
            return "Built to a tight specification";
        }
        String first = keys.get(rnd.nextInt(keys.size()));
        String phrase = "specifications include " + humanise(first) + " " + attributes.get(first);
        if (keys.size() > 1 && rnd.nextBoolean()) {
            String second = keys.get(rnd.nextInt(keys.size()));
            if (!second.equals(first)) {
                phrase += " and " + humanise(second) + " " + attributes.get(second);
            }
        }
        return "The " + phrase;
    }

    private static String humanise(String key) {
        return key.replace('_', ' ').toLowerCase();
    }

    private static double buildPrice(Random rnd, CategorySpec category) {
        double raw = category.priceMin() + rnd.nextDouble() * (category.priceMax() - category.priceMin());
        int whole = (int) Math.floor(raw);
        double[] endings = {0.00, 0.49, 0.95, 0.99};
        double price = whole + endings[rnd.nextInt(endings.length)];
        if (price < 1) {
            price = Math.max(1.0, Math.round(raw * 100) / 100.0);
        }
        return Math.round(price * 100) / 100.0;
    }

    private static List<String> buildTags(Random rnd, CategorySpec category) {
        String[] pool = category.tags();
        int wanted = 2 + rnd.nextInt(Math.max(1, Math.min(3, pool.length - 1)));
        Set<String> tags = new LinkedHashSet<>();
        int guard = 0;
        while (tags.size() < wanted && guard++ < pool.length * 3) {
            tags.add(pool[rnd.nextInt(pool.length)].toLowerCase());
        }
        return new ArrayList<>(tags);
    }

    private static List<ProductDocument.Variant> buildVariants(Random rnd, CategorySpec category, String sku) {
        int count = (category.variantHeavy() || rnd.nextInt(100) < 40) ? 2 + rnd.nextInt(3) : 1;
        List<ProductDocument.Variant> variants = new ArrayList<>(count);
        Set<String> usedCodes = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int colourIndex = rnd.nextInt(COLOURS.length);
            String colour = COLOURS[colourIndex];
            String code = COLOUR_CODES[colourIndex];
            // Never issue the same variant code twice within one product.
            int steps = 0;
            while (!usedCodes.add(code) && steps++ < COLOUR_CODES.length) {
                colourIndex = (colourIndex + 1) % COLOURS.length;
                colour = COLOURS[colourIndex];
                code = COLOUR_CODES[colourIndex];
            }

            ProductDocument.Variant variant = new ProductDocument.Variant();
            variant.setSku(sku + "-" + code);
            variant.setColor(colour);
            variant.setStock(5 + rnd.nextInt(196));
            variants.add(variant);
        }
        return variants;
    }

    private static String pick(Random rnd, String[] values) {
        return values[rnd.nextInt(values.length)];
    }

    /** "true"/"false" and numerics become real BSON types instead of strings. */
    private static Object coerce(String value) {
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.parseBoolean(value);
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            // fall through — it is a plain label such as "Stainless Steel"
        }
        return value;
    }
}
