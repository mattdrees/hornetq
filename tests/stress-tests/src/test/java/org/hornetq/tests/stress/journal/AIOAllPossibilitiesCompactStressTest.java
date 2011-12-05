/*
 * Copyright 2010 Red Hat, Inc.
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

import java.io.File;

import org.hornetq.core.config.impl.ConfigurationImpl;
import org.hornetq.core.journal.SequentialFileFactory;
import org.hornetq.core.journal.impl.AIOSequentialFileFactory;
import org.hornetq.core.journal.impl.JournalConstants;

/**
 * A NIOAllPossibilitiesCompactStressTest
 *
 * @author <a href="mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 *
 *
 */
public class AIOAllPossibilitiesCompactStressTest extends AllPossibilitiesCompactStressTest
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------
   @Override
   protected SequentialFileFactory getFileFactory() throws Exception
   {
      File file = new File(getTestDir());

      deleteDirectory(file);

      file.mkdir();

      return new AIOSequentialFileFactory(getTestDir(),
                                          JournalConstants.DEFAULT_JOURNAL_BUFFER_SIZE_AIO,
                                          1000000,
                                          false);
   }


}