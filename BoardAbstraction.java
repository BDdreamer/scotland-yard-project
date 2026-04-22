package uk.ac.bris.cs.scotlandyard.ai;

import java.util.HashMap;
import java.util.Map;

/**
 * Board abstraction for CFR training.
 * Maps 199 Scotland Yard nodes to 20 strategic zones.
 * Generated from k-means clustering on node features:
 * - connectivity, taxiEdges, busEdges, underEdges, geo_x, geo_y
 *
 * Zone characteristics:
 * Zone 6:  Underground+Bus hubs (46, 67, 128, 140, 153) - highest value
 * Zone 16: Underground peripheral (79, 111)
 * Zone 5:  Underground outer (13, 89)
 * Zone 9:  Bus corridors (29, 41, 52, 58, 72, 82, 86, 100, 116)
 * Zone 17: Taxi-only peripheral (1,2,4,5,6,7,21,30)
 * Zone 7:  Central taxi nodes
 * Zone 19: Edge taxi nodes
 */
public class BoardAbstraction {

    public static final int NUM_ZONES = 20;

    private static final Map<Integer, Integer> NODE_TO_ZONE = new HashMap<>();

    static {
        // Auto-generated zone mapping from k-means clustering
        // 20 zones based on connectivity, transport diversity, and geography
        NODE_TO_ZONE.put(1, 17);
        NODE_TO_ZONE.put(2, 17);
        NODE_TO_ZONE.put(3, 0);
        NODE_TO_ZONE.put(4, 17);
        NODE_TO_ZONE.put(5, 17);
        NODE_TO_ZONE.put(6, 17);
        NODE_TO_ZONE.put(7, 17);
        NODE_TO_ZONE.put(8, 1);
        NODE_TO_ZONE.put(9, 1);
        NODE_TO_ZONE.put(10, 12);
        NODE_TO_ZONE.put(11, 1);
        NODE_TO_ZONE.put(12, 1);
        NODE_TO_ZONE.put(13, 5);
        NODE_TO_ZONE.put(14, 1);
        NODE_TO_ZONE.put(15, 0);
        NODE_TO_ZONE.put(16, 14);
        NODE_TO_ZONE.put(17, 8);
        NODE_TO_ZONE.put(18, 8);
        NODE_TO_ZONE.put(19, 8);
        NODE_TO_ZONE.put(20, 8);
        NODE_TO_ZONE.put(21, 17);
        NODE_TO_ZONE.put(22, 0);
        NODE_TO_ZONE.put(23, 0);
        NODE_TO_ZONE.put(24, 1);
        NODE_TO_ZONE.put(25, 1);
        NODE_TO_ZONE.put(26, 1);
        NODE_TO_ZONE.put(27, 1);
        NODE_TO_ZONE.put(28, 12);
        NODE_TO_ZONE.put(29, 9);
        NODE_TO_ZONE.put(30, 17);
        NODE_TO_ZONE.put(31, 8);
        NODE_TO_ZONE.put(32, 14);
        NODE_TO_ZONE.put(33, 14);
        NODE_TO_ZONE.put(34, 0);
        NODE_TO_ZONE.put(35, 14);
        NODE_TO_ZONE.put(36, 8);
        NODE_TO_ZONE.put(37, 12);
        NODE_TO_ZONE.put(38, 12);
        NODE_TO_ZONE.put(39, 12);
        NODE_TO_ZONE.put(40, 12);
        NODE_TO_ZONE.put(41, 9);
        NODE_TO_ZONE.put(42, 12);
        NODE_TO_ZONE.put(43, 1);
        NODE_TO_ZONE.put(44, 1);
        NODE_TO_ZONE.put(45, 14);
        NODE_TO_ZONE.put(46, 6);
        NODE_TO_ZONE.put(47, 8);
        NODE_TO_ZONE.put(48, 14);
        NODE_TO_ZONE.put(49, 8);
        NODE_TO_ZONE.put(50, 8);
        NODE_TO_ZONE.put(51, 14);
        NODE_TO_ZONE.put(52, 9);
        NODE_TO_ZONE.put(53, 1);
        NODE_TO_ZONE.put(54, 12);
        NODE_TO_ZONE.put(55, 1);
        NODE_TO_ZONE.put(56, 19);
        NODE_TO_ZONE.put(57, 1);
        NODE_TO_ZONE.put(58, 9);
        NODE_TO_ZONE.put(59, 12);
        NODE_TO_ZONE.put(60, 8);
        NODE_TO_ZONE.put(61, 14);
        NODE_TO_ZONE.put(62, 14);
        NODE_TO_ZONE.put(63, 0);
        NODE_TO_ZONE.put(64, 8);
        NODE_TO_ZONE.put(65, 0);
        NODE_TO_ZONE.put(66, 14);
        NODE_TO_ZONE.put(67, 6);
        NODE_TO_ZONE.put(68, 12);
        NODE_TO_ZONE.put(69, 12);
        NODE_TO_ZONE.put(70, 1);
        NODE_TO_ZONE.put(71, 12);
        NODE_TO_ZONE.put(72, 9);
        NODE_TO_ZONE.put(73, 1);
        NODE_TO_ZONE.put(74, 12);
        NODE_TO_ZONE.put(75, 14);
        NODE_TO_ZONE.put(76, 14);
        NODE_TO_ZONE.put(77, 0);
        NODE_TO_ZONE.put(78, 14);
        NODE_TO_ZONE.put(79, 16);
        NODE_TO_ZONE.put(80, 7);
        NODE_TO_ZONE.put(81, 7);
        NODE_TO_ZONE.put(82, 9);
        NODE_TO_ZONE.put(83, 19);
        NODE_TO_ZONE.put(84, 19);
        NODE_TO_ZONE.put(85, 1);
        NODE_TO_ZONE.put(86, 9);
        NODE_TO_ZONE.put(87, 15);
        NODE_TO_ZONE.put(88, 19);
        NODE_TO_ZONE.put(89, 5);
        NODE_TO_ZONE.put(90, 7);
        NODE_TO_ZONE.put(91, 14);
        NODE_TO_ZONE.put(92, 7);
        NODE_TO_ZONE.put(93, 7);
        NODE_TO_ZONE.put(94, 2);
        NODE_TO_ZONE.put(95, 7);
        NODE_TO_ZONE.put(96, 7);
        NODE_TO_ZONE.put(97, 4);
        NODE_TO_ZONE.put(98, 4);
        NODE_TO_ZONE.put(99, 4);
        NODE_TO_ZONE.put(100, 9);
        NODE_TO_ZONE.put(101, 4);
        NODE_TO_ZONE.put(102, 15);
        NODE_TO_ZONE.put(103, 19);
        NODE_TO_ZONE.put(104, 19);
        NODE_TO_ZONE.put(105, 11);
        NODE_TO_ZONE.put(106, 7);
        NODE_TO_ZONE.put(107, 2);
        NODE_TO_ZONE.put(108, 2);
        NODE_TO_ZONE.put(109, 14);
        NODE_TO_ZONE.put(110, 14);
        NODE_TO_ZONE.put(111, 16);
        NODE_TO_ZONE.put(112, 4);
        NODE_TO_ZONE.put(113, 7);
        NODE_TO_ZONE.put(114, 18);
        NODE_TO_ZONE.put(115, 4);
        NODE_TO_ZONE.put(116, 9);
        NODE_TO_ZONE.put(117, 4);
        NODE_TO_ZONE.put(118, 4);
        NODE_TO_ZONE.put(119, 19);
        NODE_TO_ZONE.put(120, 7);
        NODE_TO_ZONE.put(121, 7);
        NODE_TO_ZONE.put(122, 2);
        NODE_TO_ZONE.put(123, 11);
        NODE_TO_ZONE.put(124, 11);
        NODE_TO_ZONE.put(125, 7);
        NODE_TO_ZONE.put(126, 4);
        NODE_TO_ZONE.put(127, 18);
        NODE_TO_ZONE.put(128, 6);
        NODE_TO_ZONE.put(129, 18);
        NODE_TO_ZONE.put(130, 10);
        NODE_TO_ZONE.put(131, 10);
        NODE_TO_ZONE.put(132, 19);
        NODE_TO_ZONE.put(133, 10);
        NODE_TO_ZONE.put(134, 4);
        NODE_TO_ZONE.put(135, 2);
        NODE_TO_ZONE.put(136, 7);
        NODE_TO_ZONE.put(137, 7);
        NODE_TO_ZONE.put(138, 7);
        NODE_TO_ZONE.put(139, 3);
        NODE_TO_ZONE.put(140, 6);
        NODE_TO_ZONE.put(141, 4);
        NODE_TO_ZONE.put(142, 11);
        NODE_TO_ZONE.put(143, 18);
        NODE_TO_ZONE.put(144, 15);
        NODE_TO_ZONE.put(145, 10);
        NODE_TO_ZONE.put(146, 4);
        NODE_TO_ZONE.put(147, 10);
        NODE_TO_ZONE.put(148, 10);
        NODE_TO_ZONE.put(149, 4);
        NODE_TO_ZONE.put(150, 3);
        NODE_TO_ZONE.put(151, 3);
        NODE_TO_ZONE.put(152, 3);
        NODE_TO_ZONE.put(153, 6);
        NODE_TO_ZONE.put(154, 2);
        NODE_TO_ZONE.put(155, 3);
        NODE_TO_ZONE.put(156, 2);
        NODE_TO_ZONE.put(157, 15);
        NODE_TO_ZONE.put(158, 4);
        NODE_TO_ZONE.put(159, 18);
        NODE_TO_ZONE.put(160, 4);
        NODE_TO_ZONE.put(161, 15);
        NODE_TO_ZONE.put(162, 19);
        NODE_TO_ZONE.put(163, 13);
        NODE_TO_ZONE.put(164, 4);
        NODE_TO_ZONE.put(165, 2);
        NODE_TO_ZONE.put(166, 3);
        NODE_TO_ZONE.put(167, 3);
        NODE_TO_ZONE.put(168, 3);
        NODE_TO_ZONE.put(169, 7);
        NODE_TO_ZONE.put(170, 3);
        NODE_TO_ZONE.put(171, 3);
        NODE_TO_ZONE.put(172, 10);
        NODE_TO_ZONE.put(173, 4);
        NODE_TO_ZONE.put(174, 10);
        NODE_TO_ZONE.put(175, 10);
        NODE_TO_ZONE.put(176, 15);
        NODE_TO_ZONE.put(177, 10);
        NODE_TO_ZONE.put(178, 10);
        NODE_TO_ZONE.put(179, 10);
        NODE_TO_ZONE.put(180, 2);
        NODE_TO_ZONE.put(181, 3);
        NODE_TO_ZONE.put(182, 3);
        NODE_TO_ZONE.put(183, 3);
        NODE_TO_ZONE.put(184, 11);
        NODE_TO_ZONE.put(185, 13);
        NODE_TO_ZONE.put(186, 3);
        NODE_TO_ZONE.put(187, 15);
        NODE_TO_ZONE.put(188, 4);
        NODE_TO_ZONE.put(189, 10);
        NODE_TO_ZONE.put(190, 15);
        NODE_TO_ZONE.put(191, 15);
        NODE_TO_ZONE.put(192, 10);
        NODE_TO_ZONE.put(193, 10);
        NODE_TO_ZONE.put(194, 10);
        NODE_TO_ZONE.put(195, 3);
        NODE_TO_ZONE.put(196, 3);
        NODE_TO_ZONE.put(197, 3);
        NODE_TO_ZONE.put(198, 3);
        NODE_TO_ZONE.put(199, 2);
    }

    /**
     * Returns the zone ID for a given board node.
     * @param node Board node (1-199)
     * @return Zone ID (0-19), or -1 if node not found
     */
    public static int getZone(int node) {
        return NODE_TO_ZONE.getOrDefault(node, -1);
    }

    /**
     * Returns a bucketed ticket count for state abstraction.
     * Reduces ticket state space for CFR information sets.
     */
    public static int bucketTickets(int count, int maxBucket) {
        return Math.min(count, maxBucket);
    }

    /**
     * Computes a compact information set key for CFR lookup.
     * Used by detectives (imperfect information).
     *
     * @param lastRevealZone  Zone of last known Mr X position
     * @param movesSinceReveal  Moves since last reveal
     * @param myZone  This detective's zone
     * @param round  Current round
     * @param taxiBucket  Bucketed taxi count (0-3)
     * @param secretBucket  Bucketed secret count (0-2)
     * @return Long hash for information set lookup
     */
    public static long computeDetectiveInfoSetKey(
            int lastRevealZone,
            int movesSinceReveal,
            int myZone,
            int round,
            int taxiBucket,
            int secretBucket) {

        long key = 0;
        key = key * NUM_ZONES + lastRevealZone;
        key = key * 6 + Math.min(movesSinceReveal, 5);
        key = key * NUM_ZONES + myZone;
        key = key * 25 + round;
        key = key * 4 + taxiBucket;
        key = key * 3 + secretBucket;
        return key;
    }

    /**
     * Computes a compact information set key for Mr X (perfect information).
     *
     * @param mrXZone  Mr X's current zone
     * @param minDetZone  Nearest detective's zone
     * @param round  Current round
     * @param taxiBucket  Bucketed taxi count (0-3)
     * @param secretBucket  Bucketed secret count (0-2)
     * @param doubleBucket  Bucketed double count (0-2)
     * @return Long hash for information set lookup
     */
    public static long computeMrXInfoSetKey(
            int mrXZone,
            int minDetZone,
            int round,
            int taxiBucket,
            int secretBucket,
            int doubleBucket) {

        long key = 0;
        key = key * NUM_ZONES + mrXZone;
        key = key * NUM_ZONES + minDetZone;
        key = key * 25 + round;
        key = key * 4 + taxiBucket;
        key = key * 3 + secretBucket;
        key = key * 3 + doubleBucket;
        return key;
    }
}
