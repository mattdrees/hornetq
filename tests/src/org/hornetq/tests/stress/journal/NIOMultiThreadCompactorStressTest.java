/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.tests.stress.journal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.hornetq.core.asyncio.impl.AsynchronousFileImpl;
import org.hornetq.core.buffers.HornetQBuffers;
import org.hornetq.core.client.ClientConsumer;
import org.hornetq.core.client.ClientMessage;
import org.hornetq.core.client.ClientProducer;
import org.hornetq.core.client.ClientSession;
import org.hornetq.core.client.ClientSessionFactory;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.config.impl.ConfigurationImpl;
import org.hornetq.core.exception.HornetQException;
import org.hornetq.core.journal.PreparedTransactionInfo;
import org.hornetq.core.journal.RecordInfo;
import org.hornetq.core.journal.impl.JournalImpl;
import org.hornetq.core.journal.impl.NIOSequentialFileFactory;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.JournalType;
import org.hornetq.tests.util.ServiceTestBase;
import org.hornetq.utils.SimpleString;

/**
 * A MultiThreadConsumerStressTest
 *
 * @author <mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 *
 *
 */
public class NIOMultiThreadCompactorStressTest extends ServiceTestBase
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   final SimpleString ADDRESS = new SimpleString("SomeAddress");

   final SimpleString QUEUE = new SimpleString("SomeQueue");

   private HornetQServer server;

   private ClientSessionFactory sf;

   protected int getNumberOfIterations()
   {
      return 3;
   }

   protected void setUp() throws Exception
   {
      super.setUp();
   }

   protected void tearDown() throws Exception
   {
      stopServer();
      super.tearDown();
   }

   public void testMultiThreadCompact() throws Throwable
   {
      setupServer(getJournalType());
      for (int i = 0; i < getNumberOfIterations(); i++)
      {
         System.out.println("######################################");
         System.out.println("test # " + i);

         internalTestProduceAndConsume();
         stopServer();

         NIOSequentialFileFactory factory = new NIOSequentialFileFactory(getJournalDir());
         JournalImpl journal = new JournalImpl(ConfigurationImpl.DEFAULT_JOURNAL_FILE_SIZE,
                                               2,
                                               0,
                                               0,
                                               factory,
                                               "hornetq-data",
                                               "hq",
                                               100);

         List<RecordInfo> committedRecords = new ArrayList<RecordInfo>();
         List<PreparedTransactionInfo> preparedTransactions = new ArrayList<PreparedTransactionInfo>();

         journal.start();
         journal.load(committedRecords, preparedTransactions, null);

         assertEquals(0, committedRecords.size());
         assertEquals(0, preparedTransactions.size());

         System.out.println("DataFiles = " + journal.getDataFilesCount());

         if (i % 2 == 0 && i > 0)
         {
            System.out.println("DataFiles = " + journal.getDataFilesCount());
            journal.forceMoveNextFile();
            assertEquals(0, journal.getDataFilesCount());
         }

         journal.stop();
         journal = null;

         setupServer(getJournalType());

      }
   }

   /**
    * @return
    */
   protected JournalType getJournalType()
   {
      return JournalType.NIO;
   }

   /**
    * @param xid
    * @throws HornetQException
    * @throws XAException
    */
   private void addEmptyTransaction(Xid xid) throws HornetQException, XAException
   {
      ClientSessionFactory sf = createInVMFactory();
      sf.setBlockOnNonPersistentSend(false);
      sf.setBlockOnAcknowledge(false);
      ClientSession session = sf.createSession(true, false, false);
      session.start(xid, XAResource.TMNOFLAGS);
      session.end(xid, XAResource.TMSUCCESS);
      session.prepare(xid);
      session.close();
      sf.close();
   }

   private void checkEmptyXID(Xid xid) throws HornetQException, XAException
   {
      ClientSessionFactory sf = createInVMFactory();
      sf.setBlockOnNonPersistentSend(false);
      sf.setBlockOnAcknowledge(false);
      ClientSession session = sf.createSession(true, false, false);

      Xid[] xids = session.recover(XAResource.TMSTARTRSCAN);
      assertEquals(1, xids.length);
      assertEquals(xid, xids[0]);

      session.rollback(xid);

      session.close();
      sf.close();
   }

   public void internalTestProduceAndConsume() throws Throwable
   {

      addBogusData(100, "LAZY-QUEUE");

      Xid xid = null;
      xid = newXID();
      addEmptyTransaction(xid);

      System.out.println(getTemporaryDir());
      boolean transactionalOnConsume = true;
      boolean transactionalOnProduce = true;
      int numberOfConsumers = 30;
      // this test assumes numberOfConsumers == numberOfProducers
      int numberOfProducers = numberOfConsumers;
      int produceMessage = 5000;
      int commitIntervalProduce = 100;
      int consumeMessage = (int)(produceMessage * 0.9);
      int commitIntervalConsume = 100;

      System.out.println("ConsumeMessages = " + consumeMessage + " produceMessage = " + produceMessage);

      // Number of messages expected to be received after restart
      int numberOfMessagesExpected = (produceMessage - consumeMessage) * numberOfConsumers;

      CountDownLatch latchReady = new CountDownLatch(numberOfConsumers + numberOfProducers);

      CountDownLatch latchStart = new CountDownLatch(1);

      ArrayList<BaseThread> threads = new ArrayList<BaseThread>();

      ProducerThread[] prod = new ProducerThread[numberOfProducers];
      for (int i = 0; i < numberOfProducers; i++)
      {
         prod[i] = new ProducerThread(i,
                                      latchReady,
                                      latchStart,
                                      transactionalOnConsume,
                                      produceMessage,
                                      commitIntervalProduce);
         prod[i].start();
         threads.add(prod[i]);
      }

      ConsumerThread[] cons = new ConsumerThread[numberOfConsumers];

      for (int i = 0; i < numberOfConsumers; i++)
      {
         cons[i] = new ConsumerThread(i,
                                      latchReady,
                                      latchStart,
                                      transactionalOnProduce,
                                      consumeMessage,
                                      commitIntervalConsume);
         cons[i].start();
         threads.add(cons[i]);
      }

      latchReady.await();
      latchStart.countDown();

      for (BaseThread t : threads)
      {
         t.join();
         if (t.e != null)
         {
            throw t.e;
         }
      }

      server.stop();

      setupServer(getJournalType());

      drainQueue(numberOfMessagesExpected, QUEUE);
      drainQueue(100, new SimpleString("LAZY-QUEUE"));

      server.stop();

      setupServer(getJournalType());
      drainQueue(0, QUEUE);
      drainQueue(0, new SimpleString("LAZY-QUEUE"));

      checkEmptyXID(xid);

   }

   /**
    * @param numberOfMessagesExpected
    * @param queue
    * @throws HornetQException
    */
   private void drainQueue(int numberOfMessagesExpected, SimpleString queue) throws HornetQException
   {
      ClientSession sess = sf.createSession(true, true);

      ClientConsumer consumer = sess.createConsumer(queue);

      sess.start();

      for (int i = 0; i < numberOfMessagesExpected; i++)
      {
         ClientMessage msg = consumer.receive(5000);
         assertNotNull(msg);

         if (i % 100 == 0)
         {
            // System.out.println("Received #" + i + "  on thread after start");
         }
         msg.acknowledge();
      }

      assertNull(consumer.receiveImmediate());

      sess.close();
   }

   /**
    * @throws HornetQException
    */
   private void addBogusData(int nmessages, String queue) throws HornetQException
   {
      ClientSession session = sf.createSession(false, false);
      try
      {
         session.createQueue(queue, queue, true);
      }
      catch (Exception ignored)
      {
      }

      ClientProducer prod = session.createProducer(queue);
      for (int i = 0; i < nmessages; i++)
      {
         ClientMessage msg = session.createClientMessage(true);
         msg.getBodyBuffer().writeBytes(new byte[1024]);
         prod.send(msg);
      }
      session.commit();

      session.start();

      ClientConsumer cons = session.createConsumer(queue);
      assertNotNull(cons.receive(1000));
      session.rollback();
      session.close();
   }

   protected void stopServer() throws Exception
   {
      try
      {
         if (server != null && server.isStarted())
         {
            server.stop();
         }
      }
      catch (Throwable e)
      {
         e.printStackTrace(System.out); // System.out => junit reports
      }

      sf = null;
   }

   private void setupServer(JournalType journalType) throws Exception, HornetQException
   {
      if (!AsynchronousFileImpl.isLoaded())
      {
         journalType = JournalType.NIO;
      }
      if (server == null)
      {
         Configuration config = createDefaultConfig(true);
         config.setJournalFileSize(ConfigurationImpl.DEFAULT_JOURNAL_FILE_SIZE);

         config.setJournalType(journalType);
         config.setJMXManagementEnabled(false);

         config.setJournalFileSize(ConfigurationImpl.DEFAULT_JOURNAL_FILE_SIZE);
         config.setJournalMinFiles(ConfigurationImpl.DEFAULT_JOURNAL_MIN_FILES);

         config.setJournalCompactMinFiles(ConfigurationImpl.DEFAULT_JOURNAL_COMPACT_MIN_FILES);
         config.setJournalCompactPercentage(ConfigurationImpl.DEFAULT_JOURNAL_COMPACT_PERCENTAGE);

         // This test is supposed to not sync.. All the ACKs are async, and it was supposed to not sync
         config.setJournalSyncNonTransactional(false);

         // config.setJournalCompactMinFiles(0);
         // config.setJournalCompactPercentage(0);

         server = createServer(true, config);
      }

      server.start();

      sf = createNettyFactory();
      sf.setBlockOnPersistentSend(false);
      sf.setBlockOnAcknowledge(false);

      ClientSession sess = sf.createSession();

      try
      {
         sess.createQueue(ADDRESS, QUEUE, true);
      }
      catch (Exception ignored)
      {
      }

      sess.close();

      sf = createInVMFactory();
   }

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   class BaseThread extends Thread
   {
      Throwable e;

      final CountDownLatch latchReady;

      final CountDownLatch latchStart;

      final int numberOfMessages;

      final int commitInterval;

      final boolean transactional;

      BaseThread(String name,
                 CountDownLatch latchReady,
                 CountDownLatch latchStart,
                 boolean transactional,
                 int numberOfMessages,
                 int commitInterval)
      {
         super(name);
         this.transactional = transactional;
         this.latchReady = latchReady;
         this.latchStart = latchStart;
         this.commitInterval = commitInterval;
         this.numberOfMessages = numberOfMessages;
      }

   }

   class ProducerThread extends BaseThread
   {
      ProducerThread(int id,
                     CountDownLatch latchReady,
                     CountDownLatch latchStart,
                     boolean transactional,
                     int numberOfMessages,
                     int commitInterval)
      {
         super("ClientProducer:" + id, latchReady, latchStart, transactional, numberOfMessages, commitInterval);
      }

      public void run()
      {
         ClientSession session = null;
         latchReady.countDown();
         try
         {
            latchStart.await();
            session = sf.createSession(!transactional, !transactional);
            ClientProducer prod = session.createProducer(ADDRESS);
            for (int i = 0; i < numberOfMessages; i++)
            {
               if (transactional)
               {
                  if (i % commitInterval == 0)
                  {
                     session.commit();
                  }
               }
               if (i % 100 == 0)
               {
                  // System.out.println(Thread.currentThread().getName() + "::sent #" + i);
               }
               ClientMessage msg = session.createClientMessage(true);
            
               prod.send(msg);
            }

            if (transactional)
            {
               session.commit();
            }

            System.out.println("Thread " + Thread.currentThread().getName() +
                               " sent " +
                               numberOfMessages +
                               "  messages");
         }
         catch (Throwable e)
         {
            e.printStackTrace();
            this.e = e;
         }
         finally
         {
            try
            {
               session.close();
            }
            catch (Throwable e)
            {
               this.e = e;
            }
         }
      }
   }

   class ConsumerThread extends BaseThread
   {
      ConsumerThread(int id,
                     CountDownLatch latchReady,
                     CountDownLatch latchStart,
                     boolean transactional,
                     int numberOfMessages,
                     int commitInterval)
      {
         super("ClientConsumer:" + id, latchReady, latchStart, transactional, numberOfMessages, commitInterval);
      }

      public void run()
      {
         ClientSession session = null;
         latchReady.countDown();
         try
         {
            latchStart.await();
            session = sf.createSession(!transactional, !transactional);
            session.start();
            ClientConsumer cons = session.createConsumer(QUEUE);
            for (int i = 0; i < numberOfMessages; i++)
            {
               ClientMessage msg = cons.receive(60 * 1000);
               msg.acknowledge();
               if (i % commitInterval == 0)
               {
                  session.commit();
               }
               if (i % 100 == 0)
               {
                  // System.out.println(Thread.currentThread().getName() + "::received #" + i);
               }
            }

            System.out.println("Thread " + Thread.currentThread().getName() +
                               " received " +
                               numberOfMessages +
                               " messages");

            session.commit();
         }
         catch (Throwable e)
         {
            this.e = e;
         }
         finally
         {
            try
            {
               session.close();
            }
            catch (Throwable e)
            {
               this.e = e;
            }
         }
      }
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}