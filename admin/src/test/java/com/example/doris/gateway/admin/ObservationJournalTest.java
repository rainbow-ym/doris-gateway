package com.example.doris.gateway.admin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.doris.gateway.observability.Observation.*;
class ObservationJournalTest {
 @TempDir Path root;
 CryptoObservations.Event event(){CryptoObservations.Event e=new CryptoObservations.Event();e.requestId=UUID.randomUUID().toString();e.spanId=UUID.randomUUID().toString();e.operation=Operation.DECRYPT_FILE;e.reason=Reason.OK;return e;}
 @Test void rotatesTwoBoundedFilesAndCountsQueueDrops()throws Exception{
  ObservationJournal journal=new ObservationJournal(root,1024,1);
  for(int i=0;i<500;i++)journal.offer(event());journal.close();
  assertTrue(journal.status().get("written")>0);assertTrue(journal.status().get("dropped")>0);
  try(java.util.stream.Stream<Path> paths=Files.list(root)){assertTrue(paths.count()<=2);}
  try(java.util.stream.Stream<Path> paths=Files.list(root)){paths.forEach(p->{try{assertTrue(Files.size(p)<=1024);}catch(Exception e){throw new RuntimeException(e);}});}
 }
 @Test void unwritableDestinationDoesNotThrowOnBusinessThread()throws Exception{
  Path regular=root.resolve("not-directory");Files.write(regular,new byte[]{1});
  ObservationJournal journal=new ObservationJournal(regular,1024,1);
  assertDoesNotThrow(()->journal.offer(event()));journal.close();assertEquals(1L,journal.status().get("errors"));
 }
}
