package com.demo.securityapp.crypto;

import com.demo.securityapp.dto.MerkleProofStep;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MerkleTreeTest {
    @Test void emptyRootIsStandardSha256Empty(){assertThat(HexFormat.of().formatHex(MerkleTree.root(List.of()))).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");}
    @Test void everyInclusionProofWorksForBalancedAndUnbalancedTrees(){
        for(int count=1;count<=65;count++){
            List<byte[]> leaves=new ArrayList<>();for(int i=0;i<count;i++)leaves.add(MerkleTree.leaf(("event-"+i).getBytes(StandardCharsets.UTF_8)));
            for(int index=0;index<count;index++){
                List<MerkleProofStep> proof=MerkleTree.proof(leaves,index);
                assertThat(MerkleTree.verify(leaves.get(index),proof,MerkleTree.root(leaves))).isTrue();
                assertThat(MerkleTree.verify(leaves.get(index),index,count,proof,MerkleTree.root(leaves))).isTrue();
                if(count>1)assertThat(MerkleTree.verify(leaves.get(index),(index+1)%count,count,proof,MerkleTree.root(leaves))).isFalse();
                assertThat(MerkleTree.verify(MerkleTree.leaf("tampered".getBytes(StandardCharsets.UTF_8)),proof,MerkleTree.root(leaves))).isFalse();
            }
        }
    }
    @Test void leafAndNodeDomainsAreDistinct(){byte[] a=new byte[32],b=new byte[32],both=new byte[64];assertThat(MerkleTree.leaf(both)).isNotEqualTo(MerkleTree.node(a,b));}
    @Test void deletionAndReorderingChangeRoot(){List<byte[]> leaves=List.of(MerkleTree.leaf(new byte[]{1}),MerkleTree.leaf(new byte[]{2}),MerkleTree.leaf(new byte[]{3}));assertThat(MerkleTree.root(leaves)).isNotEqualTo(MerkleTree.root(List.of(leaves.get(0),leaves.get(2))));assertThat(MerkleTree.root(leaves)).isNotEqualTo(MerkleTree.root(List.of(leaves.get(1),leaves.get(0),leaves.get(2))));}
}
