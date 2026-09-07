package com.demo.securityapp;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.*;

/** RFC 9162 tree hashing (0x00 leaves, 0x01 internal nodes), without claiming a CT server. */
public final class MerkleTree {
    private MerkleTree() {}
    public static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static byte[] leaf(byte[] value) { return hash(join(new byte[]{0}, value)); }
    public static byte[] node(byte[] left, byte[] right) { return hash(join(new byte[]{1}, left, right)); }
    public static byte[] root(List<byte[]> leaves) {
        if (leaves.isEmpty()) return hash(new byte[0]);
        if (leaves.size() == 1) return leaves.getFirst();
        int split = Integer.highestOneBit(leaves.size() - 1);
        return node(root(leaves.subList(0,split)), root(leaves.subList(split,leaves.size())));
    }
    public static List<Step> proof(List<byte[]> leaves, int index) {
        if (index < 0 || index >= leaves.size()) throw new IllegalArgumentException("Invalid leaf index");
        List<Step> result = new ArrayList<>();
        proof(leaves,index,result);
        return result;
    }
    private static void proof(List<byte[]> leaves, int index, List<Step> result) {
        if (leaves.size() == 1) return;
        int split = Integer.highestOneBit(leaves.size() - 1);
        if(index < split) {
            proof(leaves.subList(0,split),index,result);
            result.add(new Step(false, HexFormat.of().formatHex(root(leaves.subList(split,leaves.size())))));
        } else {
            proof(leaves.subList(split,leaves.size()),index-split,result);
            result.add(new Step(true, HexFormat.of().formatHex(root(leaves.subList(0,split)))));
        }
    }
    public static boolean verify(byte[] leaf, List<Step> proof, byte[] root) {
        if(leaf==null||leaf.length!=32||root==null||root.length!=32||proof==null)return false;
        byte[] current = leaf;
        try {
            for (Step step : proof) {
                byte[] other = HexFormat.of().parseHex(step.hash());
                if(other.length != 32) return false;
                current = step.left() ? node(other,current) : node(current,other);
            }
            return MessageDigest.isEqual(current,root);
        } catch (IllegalArgumentException | NullPointerException invalid) { return false; }
    }
    /** Verify both membership and the claimed leaf position/tree size. */
    public static boolean verify(byte[] leaf,int index,int treeSize,List<Step> proof,byte[] root) {
        if(treeSize<1||index<0||index>=treeSize||proof==null)return false;
        List<Boolean> directions=new ArrayList<>();
        directions(treeSize,index,directions);
        if(directions.size()!=proof.size())return false;
        for(int i=0;i<directions.size();i++)if(proof.get(i)==null||directions.get(i)!=proof.get(i).left())return false;
        return verify(leaf,proof,root);
    }
    private static void directions(int size,int index,List<Boolean> directions){
        if(size==1)return;
        int split=Integer.highestOneBit(size-1);
        if(index<split){directions(split,index,directions);directions.add(false);}
        else{directions(size-split,index-split,directions);directions.add(true);}
    }
    public record Step(boolean left, String hash) {}
    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for(byte[] part:parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
