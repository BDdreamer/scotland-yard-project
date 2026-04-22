package uk.ac.bris.cs.scotlandyard.ai;

import java.util.*;

/**
 * Calculates optimal zone centers using k-means clustering with shortest-path distance.
 * 
 * This is the mathematically correct approach vs hand-picking centers by intuition.
 * Run this once to generate optimal zone centers, then hardcode them in ZoneStrategy.
 */
public class ZoneCenterCalculator {
    
    private final GraphAnalyzer graphAnalyzer;
    private final int totalNodes;
    private final int k; // Number of clusters
    
    public ZoneCenterCalculator(GraphAnalyzer graphAnalyzer, int totalNodes, int k) {
        this.graphAnalyzer = graphAnalyzer;
        this.totalNodes = totalNodes;
        this.k = k;
    }
    
    /**
     * Run k-means clustering to find optimal zone centers.
     * 
     * @param maxIterations Maximum iterations before stopping
     * @return Array of optimal zone center node IDs
     */
    public int[] calculateOptimalCenters(int maxIterations) {
        // Initialize: pick k random nodes as initial centers
        int[] centers = initializeRandomCenters();
        Map<Integer, Set<Integer>> clusters = new HashMap<>();
        
        for (int iter = 0; iter < maxIterations; iter++) {
            // Step 1: Assign each node to nearest center
            clusters = assignNodesToClusters(centers);
            
            // Step 2: Recalculate centers as medoids of clusters
            int[] newCenters = calculateMedoids(clusters);
            
            // Check convergence
            if (Arrays.equals(centers, newCenters)) {
                System.out.println("K-means converged after " + iter + " iterations");
                break;
            }
            
            centers = newCenters;
        }
        
        // Sort centers by node ID for consistency
        Arrays.sort(centers);
        
        return centers;
    }
    
    /**
     * Initialize k random centers.
     */
    private int[] initializeRandomCenters() {
        Random rand = new Random(42); // Fixed seed for reproducibility
        Set<Integer> selected = new HashSet<>();
        int[] centers = new int[k];
        
        for (int i = 0; i < k; i++) {
            int node;
            do {
                node = rand.nextInt(totalNodes) + 1;
            } while (selected.contains(node));
            
            selected.add(node);
            centers[i] = node;
        }
        
        return centers;
    }
    
    /**
     * Assign each node to its nearest center using shortest-path distance.
     */
    private Map<Integer, Set<Integer>> assignNodesToClusters(int[] centers) {
        Map<Integer, Set<Integer>> clusters = new HashMap<>();
        
        // Initialize empty clusters
        for (int center : centers) {
            clusters.put(center, new HashSet<>());
        }
        
        // Assign each node to nearest center
        for (int node = 1; node <= totalNodes; node++) {
            int nearestCenter = findNearestCenter(node, centers);
            clusters.get(nearestCenter).add(node);
        }
        
        return clusters;
    }
    
    /**
     * Find the nearest center to a given node.
     */
    private int findNearestCenter(int node, int[] centers) {
        int nearestCenter = centers[0];
        int minDistance = Integer.MAX_VALUE;
        
        for (int center : centers) {
            int distance = graphAnalyzer.calculateShortestPath(node, center);
            if (distance >= 0 && distance < minDistance) {
                minDistance = distance;
                nearestCenter = center;
            }
        }
        
        return nearestCenter;
    }
    
    /**
     * Calculate medoid (geometric median) for each cluster.
     * 
     * The medoid is the node in the cluster that minimizes the sum of distances
     * to all other nodes in the cluster. This is the "center" of the cluster.
     */
    private int[] calculateMedoids(Map<Integer, Set<Integer>> clusters) {
        int[] medoids = new int[k];
        int idx = 0;
        
        for (Map.Entry<Integer, Set<Integer>> entry : clusters.entrySet()) {
            Set<Integer> cluster = entry.getValue();
            
            if (cluster.isEmpty()) {
                // Empty cluster - keep old center
                medoids[idx++] = entry.getKey();
                continue;
            }
            
            int bestMedoid = entry.getKey();
            double minTotalDistance = Double.MAX_VALUE;
            
            // Try each node in cluster as potential medoid
            for (int candidate : cluster) {
                double totalDistance = 0;
                
                // Calculate sum of distances to all other nodes in cluster
                for (int other : cluster) {
                    if (candidate != other) {
                        int dist = graphAnalyzer.calculateShortestPath(candidate, other);
                        if (dist >= 0) {
                            totalDistance += dist;
                        } else {
                            totalDistance += 999; // Unreachable - high penalty
                        }
                    }
                }
                
                if (totalDistance < minTotalDistance) {
                    minTotalDistance = totalDistance;
                    bestMedoid = candidate;
                }
            }
            
            medoids[idx++] = bestMedoid;
        }
        
        return medoids;
    }
    
    /**
     * Evaluate quality of zone centers.
     * Returns average within-cluster distance (lower = better).
     */
    public double evaluateCenterQuality(int[] centers) {
        Map<Integer, Set<Integer>> clusters = assignNodesToClusters(centers);
        double totalDistance = 0;
        int totalPairs = 0;
        
        for (Set<Integer> cluster : clusters.values()) {
            for (int node1 : cluster) {
                for (int node2 : cluster) {
                    if (node1 < node2) {
                        int dist = graphAnalyzer.calculateShortestPath(node1, node2);
                        if (dist >= 0) {
                            totalDistance += dist;
                            totalPairs++;
                        }
                    }
                }
            }
        }
        
        return totalPairs == 0 ? 0 : totalDistance / totalPairs;
    }
    
    /**
     * Print zone centers and their cluster sizes.
     */
    public void printCenterAnalysis(int[] centers) {
        Map<Integer, Set<Integer>> clusters = assignNodesToClusters(centers);
        
        System.out.println("=== Zone Center Analysis ===");
        System.out.println("Number of zones: " + k);
        System.out.println("Total nodes: " + totalNodes);
        System.out.println();
        
        int zoneNum = 1;
        for (int center : centers) {
            Set<Integer> cluster = clusters.get(center);
            int clusterSize = cluster == null ? 0 : cluster.size();
            int connectivity = graphAnalyzer.getConnectivity(center);
            
            System.out.printf("Zone %d: Center = %d, Size = %d nodes, Connectivity = %d%n",
                zoneNum++, center, clusterSize, connectivity);
        }
        
        System.out.println();
        System.out.printf("Average within-cluster distance: %.2f%n", evaluateCenterQuality(centers));
        System.out.println();
        System.out.println("Optimal centers array:");
        System.out.print("private static final int[] ZONE_CENTERS = {");
        for (int i = 0; i < centers.length; i++) {
            System.out.print(centers[i]);
            if (i < centers.length - 1) System.out.print(", ");
        }
        System.out.println("};");
    }
    
    /**
     * Compare hand-picked centers vs calculated centers.
     */
    public void compareWithHandPicked(int[] handPicked) {
        System.out.println("=== Comparison: Hand-Picked vs Calculated ===");
        System.out.println();
        
        System.out.println("Hand-picked centers:");
        System.out.println(Arrays.toString(handPicked));
        double handPickedQuality = evaluateCenterQuality(handPicked);
        System.out.printf("Quality (avg within-cluster distance): %.2f%n", handPickedQuality);
        System.out.println();
        
        int[] calculated = calculateOptimalCenters(50);
        System.out.println("Calculated centers:");
        System.out.println(Arrays.toString(calculated));
        double calculatedQuality = evaluateCenterQuality(calculated);
        System.out.printf("Quality (avg within-cluster distance): %.2f%n", calculatedQuality);
        System.out.println();
        
        if (calculatedQuality < handPickedQuality) {
            double improvement = ((handPickedQuality - calculatedQuality) / handPickedQuality) * 100;
            System.out.printf("✓ Calculated centers are %.1f%% better%n", improvement);
        } else {
            double degradation = ((calculatedQuality - handPickedQuality) / handPickedQuality) * 100;
            System.out.printf("✗ Hand-picked centers are %.1f%% better%n", degradation);
        }
    }
    
    /**
     * Main method for standalone execution.
     * Run this to generate optimal zone centers for your graph.
     */
    public static void main(String[] args) {
        // This would need to be run with actual graph data
        System.out.println("Zone Center Calculator");
        System.out.println("======================");
        System.out.println();
        System.out.println("To use:");
        System.out.println("1. Load your Scotland Yard graph");
        System.out.println("2. Create GraphAnalyzer");
        System.out.println("3. Run: new ZoneCenterCalculator(analyzer, 199, 10).calculateOptimalCenters(50)");
        System.out.println("4. Replace ZONE_CENTERS in ZoneStrategy.java with output");
    }
}
