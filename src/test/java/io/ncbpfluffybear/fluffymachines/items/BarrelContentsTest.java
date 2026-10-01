package io.ncbpfluffybear.fluffymachines.items;

import io.ncbpfluffybear.fluffymachines.listeners.BarrelItemFrameListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit value doubles; actual native item/metadata and restart checks are separate. */
class BarrelContentsTest {
    record Stack(String identity, long amount) {}
    private Stack item(String id,long n) { return new Stack(id,n); }
    private BarrelContents<Stack> inspect(long n,Stack d,Stack a,Stack b) {
        return BarrelContents.inspect(n,d,a,b,(x,y)->x.identity().equals(y.identity()),Stack::amount);
    }
    @Test void fullyEmptyHasNoIdentity() { var v=inspect(0,null,null,null);assertTrue(v.safe());assertNull(v.item());assertEquals(0,v.amount()); }
    @Test void firstAcceptedBufferedItemRemainsRegistered() { var a=item("diamond-owner",1);var v=inspect(0,a,a,null);assertTrue(v.safe());assertSame(a,v.item());assertEquals(1,v.amount()); }
    @Test void oldMissingDisplayCanUseUnambiguousFirstBuffer() { var a=item("diamond",37);var v=inspect(0,null,a,null);assertTrue(v.safe());assertSame(a,v.item());assertEquals(37,v.amount()); }
    @Test void secondBufferAlsoRetainsIdentity() { var a=item("diamond",1);assertSame(a,inspect(0,null,null,a).item()); }
    @Test void matchingBuffersCombineQuantities() { var v=inspect(0,null,item("d",64),item("d",7));assertTrue(v.safe());assertEquals(71,v.amount()); }
    @Test void conflictingBuffersDoNotInventRegistration() { var v=inspect(0,null,item("a",64),item("b",64));assertFalse(v.safe());assertNull(v.item()); }
    @Test void ownerMetadataMismatchIsNotSameItem() { assertFalse(inspect(7,item("diamond-owner-a",1),item("diamond-owner-b",1),null).safe()); }
    @Test void displayAndSecondBufferMustAgree() { assertFalse(inspect(0,item("a",1),null,item("b",1)).safe()); }
    @Test void positiveUnknownCounterIsNotRecoveredFromArbitraryBuffer() { assertFalse(inspect(99,null,item("a",1),item("a",1)).safe()); }
    @Test void positiveUnknownCounterWithoutBuffersIsNotEmpty() { assertFalse(inspect(99,null,null,null).safe()); }
    @Test void negativeStoredCountIsNotNormalized() { assertFalse(inspect(-1,item("a",1),null,null).safe()); }
    @Test void completeEmptyMayForgetPriorType() { var v=inspect(0,item("a",1),null,null);assertTrue(v.safe());assertNull(v.item()); }
    @Test void knownPositiveCountRetainsItsType() { var d=item("a",1);var v=inspect(12345,d,null,null);assertSame(d,v.item());assertEquals(12345,v.amount()); }
    @Test void integerMaximumPlusBuffersIsRepresentedAsLong() { assertEquals(2147483775L,inspect(Integer.MAX_VALUE,item("a",1),item("a",64),item("a",64)).amount()); }
    @Test void arithmeticOverflowIsRefused() { assertFalse(inspect(Long.MAX_VALUE,item("a",1),item("a",1),null).safe()); }
    @Test void nonPositiveBufferValueIsRefused() { assertFalse(inspect(0,null,item("a",0),null).safe());assertFalse(inspect(0,null,item("a",-1),null).safe()); }
    @Test void classifierDoesNotMutateInputOrConsumeDisplayAmount() { var d=item("a",999);var a=item("a",37);assertEquals(47,inspect(10,d,a,null).amount());assertEquals(999,d.amount());assertEquals(37,a.amount()); }
    @Test void quantitiesDoNotAffectIdentityMatching() { assertTrue(inspect(1,item("a",1),item("a",64),item("a",5)).safe()); }
    @Test void generatedMatchingLayoutsConserveAllThreeStorageLocations() { Random r=new Random(47121);for(int i=0;i<5000;i++){int n=r.nextInt(Integer.MAX_VALUE),a=r.nextInt(65),b=r.nextInt(65);var v=inspect(n,item("exact-owner",1),a==0?null:item("exact-owner",a),b==0?null:item("exact-owner",b));assertTrue(v.safe());assertEquals((long)n+a+b,v.amount());} }
    @Test void frameListenerNeverReceivesAlreadyCancelledEvents() throws Exception { var m=BarrelItemFrameListener.class.getMethod("onBarrelItemFrameClick",PlayerInteractEntityEvent.class);assertTrue(m.getAnnotation(EventHandler.class).ignoreCancelled()); }
}
