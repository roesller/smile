/**
 * ****************************************************************************
 * Copyright (c) 2010 Haifeng Li
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * *****************************************************************************
 */
package smile.neighbor;

import smile.math.distance.HammingDistance;
import smile.util.MaxHeap;

import java.lang.reflect.Array;
import java.util.*;

import static smile.hash.SimHash.simhash64;

/**
 *
 * Locality-Sensitive Hashing for Signatures.
 * LSH is an efficient algorithm for approximate nearest neighbor search
 * in high dimensional spaces by performing probabilistic dimension reduction of data.
 * The basic idea is to hash the input items so that similar items are mapped to the same
 * buckets with high probability (the number of buckets being much smaller
 * than the universe of possible input items).
 * To avoid computing the similarity of every pair of sets or their signatures.
 * If we are given signatures for the sets, we may divide them into bands, and only
 * measure the similarity of a pair of sets if they are identical in at least one band.
 * By choosing the size of bands appropriately, we can eliminate from
 * consideration most of the pairs that do not meet our threshold of similarity.
 *
 * <h2>References</h2>
 * <ol>
 * <li>Moses S. Charikar. Similarity Estimation Techniques from Rounding Algorithms</li>
 * </ol>
 *
 * @see LSH
 * @author Qiyang Zuo
 *
 */
public class SNLSH<E> implements NearestNeighborSearch<List<String>, E>, KNNSearch<List<String>, E>, RNNSearch<List<String>, E> {


    private final int bandSize;
    private final long mask;
    private static final int BITS = 64;
    /**
     * Signature fractions
     */
    private Band[] bands;
    /**
     * The data objects.
     */
    private List<E> data;
    /**
     * The keys of data objects.
     */
    private List<List<String>> keys;
    /**
     * signatures generated by simhash
     */
    private List<Long> signs;

    /**
     * Whether to exclude query object self from the neighborhood.
     */
    private boolean identicalExcluded = true;

    @SuppressWarnings("unchecked")
    public SNLSH(int bandSize) {
        if (bandSize < 2 || bandSize > 32) {
            throw new IllegalArgumentException("Invalid band size!");
        }
        this.bandSize = bandSize;
        bands = (Band[]) Array.newInstance(Band.class, bandSize);
        Arrays.fill(bands, new Band());
        this.mask = -1 >>> (BITS / bandSize * (bandSize - 1));
        data = new ArrayList<E>();
        keys = new ArrayList<List<String>>();
        signs = new ArrayList<Long>();
    }

    public void put(List<String> tokens, E v) {
        int index = data.size();
        data.add(v);
        keys.add(tokens);
        long sign = simhash64(tokens);
        signs.add(sign);
        for (int i = 0; i < bands.length; i++) {
            long bandKey = bandHash(sign, i);
            Bucket bucket = bands[i].get(bandKey);
            if (bucket == null) {
                bucket = new Bucket();
            }
            bucket.add(index);
            bands[i].put(bandKey, bucket);
        }
    }

    public Neighbor<List<String>, E>[] knn(List<String> q, int k) {
        if(k < 1) {
            throw new IllegalArgumentException("Invalid k: " + k);
        }
        long fpq = simhash64(q);
        Set<Integer> candidates = obtainCandidates(q);
        @SuppressWarnings("unchecked")
        Neighbor<List<String>, E>[] neighbors = (Neighbor<List<String>, E>[])Array.newInstance(Neighbor.class, k);
        MaxHeap<Neighbor<List<String>, E>> heap = new MaxHeap<Neighbor<List<String>, E>>(neighbors);
        for (int index : candidates) {
            long sign = signs.get(index);
            double distance = HammingDistance.d(fpq, sign);
            if (!keys.get(index).equals(q) && identicalExcluded) {
                heap.add(new Neighbor<List<String>, E>(keys.get(index), data.get(index), index, distance));
            }
        }
        return heap.toSortedArray();
    }

    public Neighbor<List<String>, E> nearest(List<String> q) {
        Neighbor<List<String>, E>[] ns = knn(q, 1);
        if(ns.length>0) {
            return ns[0];
        }
        return new Neighbor<List<String>, E>(null, null, -1, Double.MAX_VALUE);
    }

    public void range(List<String> q, double radius, List<Neighbor<List<String>, E>> neighbors) {
        if (radius <= 0.0) {
            throw new IllegalArgumentException("Invalid radius: " + radius);
        }
        long fpq = simhash64(q);
        Set<Integer> candidates = obtainCandidates(q);
        for (int index : candidates) {
            double distance = HammingDistance.d(fpq, signs.get(index));
            if (distance <= radius) {
                if (keys.get(index).equals(q) && identicalExcluded) {
                    continue;
                }
                neighbors.add(new Neighbor<List<String>, E>(keys.get(index), data.get(index), index, distance));
            }
        }
    }

    private class Band extends LinkedHashMap<Long, Bucket> {}

    private class Bucket extends LinkedList<Integer> {}

    private long bandHash(long hash, int bandNum) {
        return hash >>> ((bandNum * (BITS / this.bandSize))) & mask;
    }




    private Set<Integer> obtainCandidates(List<String> q) {
        Set<Integer> candidates = new HashSet<Integer>();
        long sign = simhash64(q);
        for (int i = 0; i < bands.length; i++) {
            long bandKey = bandHash(sign, i);
            Bucket bucket = bands[i].get(bandKey);
            if (bucket != null) {
                candidates.addAll(bucket);
            }
        }
        return candidates;
    }
}
