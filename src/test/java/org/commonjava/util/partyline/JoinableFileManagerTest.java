/**
 * Copyright (C) 2015 Red Hat, Inc. (jdcasey@commonjava.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.util.partyline;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.commonjava.cdi.util.weft.ThreadContext;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.commonjava.util.partyline.LockOwner.PARTYLINE_LOCK_OWNER;
import static org.commonjava.util.partyline.fixture.ThreadDumper.timeoutRule;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class JoinableFileManagerTest
    extends AbstractJointedIOTest
{

    private static final long SHORT_TIMEOUT = 10;

    @Rule
    public TestRule timeout = timeoutRule( 30, TimeUnit.SECONDS );

    private final JoinableFileManager mgr = new JoinableFileManager();

    @Test
    public void lockDirThenLockFile()
            throws IOException, InterruptedException
    {
        File dir = temp.newFolder();
        File child = dir.toPath().resolve("child.txt").toFile();

        boolean dirLocked = mgr.lock( dir, 2000, LockLevel.write );
        assertThat( dirLocked, equalTo( true ) );
        assertThat( mgr.isWriteLocked( dir ), equalTo( true ) );
        assertThat( mgr.isLockedByCurrentThread( dir ), equalTo( true ) );

        try (OutputStream out = mgr.openOutputStream( child, 2000 ))
        {
            assertThat( mgr.isWriteLocked( child ), equalTo( true ) );
            IOUtils.write( "This is a test", out );
        }

        boolean unlocked = mgr.unlock( dir );
        assertThat( unlocked, equalTo( true ) );
        assertThat( mgr.isWriteLocked( dir ), equalTo( false ) );

        assertThat( mgr.isWriteLocked( child ), equalTo( false ) );
    }

    @Test
    public void lockDirThenLockTwoFilesWithShortTimeout()
            throws IOException, InterruptedException
    {
        lockDirThenLockNFilesWithTimeout( 2, 2000 );
    }

    @Test
    public void lockDirThenLockFourFilesWithShortTimeout()
            throws IOException, InterruptedException
    {
        lockDirThenLockNFilesWithTimeout( 4, 2000 );
    }

    @Test
    public void lockDirThenLockTwoFilesWithLongTimeout()
            throws IOException, InterruptedException
    {
        lockDirThenLockNFilesWithTimeout( 2, Long.MAX_VALUE );
    }

    @Test
    public void lockDirThenLockFourFilesWithLongTimeout()
            throws IOException, InterruptedException
    {
        lockDirThenLockNFilesWithTimeout( 4, Long.MAX_VALUE );
    }

    private void lockDirThenLockNFilesWithTimeout( final int filesNum, final long timeout )
            throws IOException, InterruptedException
    {
        File dir = temp.newFolder();

        final Map<String, File> files = new HashMap<>( filesNum );

        for ( int i = 1; i <= filesNum; i++ )
        {
            final String file = "child" + i + ".txt";
            files.put( file, dir.toPath().resolve( file ).toFile() );
        }

        boolean dirLocked = mgr.lock( dir, timeout, LockLevel.write );
        assertThat( dirLocked, equalTo( true ) );
        assertThat( mgr.isWriteLocked( dir ), equalTo( true ) );
        assertThat( mgr.isLockedByCurrentThread( dir ), equalTo( true ) );

        Map<String, OutputStream> fileOuts = new HashMap<>( filesNum );

        for ( int i = 1; i <= filesNum; i++ )
        {
            final String fileName = "child" + i + ".txt";
            final File f = files.get( "child" + i + ".txt" );
            fileOuts.put( fileName, mgr.openOutputStream( f, timeout ) );
        }

        for (int i=1; i<= filesNum; i++){
            final File f = files.get( "child" + i + ".txt" );
            assertThat( mgr.isWriteLocked( f ), equalTo( true ) );
        }

        for ( int i = filesNum; i > 1; i-- )
        {
            final OutputStream out = fileOuts.get( "child" + i + ".txt" );
            IOUtils.write( "This is a test", out );
            out.close();
        }

        assertThat( mgr.isWriteLocked( dir ), equalTo( true ) );

        final OutputStream out1 = fileOuts.get( "child1.txt" );
        IOUtils.write( "This is a test", out1 );
        out1.close();

        boolean unlocked = mgr.unlock( dir );
        assertThat( unlocked, equalTo( true ) );
        assertThat( mgr.isWriteLocked( dir ), equalTo( false ) );

        for ( int i = filesNum; i > 0; i-- )
        {
            final File f = files.get( "child" + i + ".txt" );
            assertThat( mgr.isWriteLocked( f ), equalTo( false ) );
        }

    }

    @Test
    @Ignore("A case that needs fix")
    public void lockTwiceForOneDir()
            throws IOException, InterruptedException
    {
        File dir = temp.newFolder();
        mgr.lock( dir, Long.MAX_VALUE, LockLevel.write );
        assertThat( mgr.isWriteLocked( dir ), equalTo( true ) );
        mgr.lock( dir, Long.MAX_VALUE, LockLevel.write );
        assertThat( mgr.isWriteLocked( dir ), equalTo( true ) );
        mgr.unlock( dir );
        assertThat( mgr.isWriteLocked( dir ), equalTo( true ) );
        mgr.unlock( dir );
        assertThat( mgr.isWriteLocked( dir ), equalTo( false ) );
    }

    @Test
    public void twoFileReaders_CleanupFileEntryOnLastClose()
            throws Exception
    {
        String src = "This is a test";

        File f = temp.newFile();
        FileUtils.write( f, src );

        int count = 2;
        CountDownLatch start = new CountDownLatch( count );
        CountDownLatch end = new CountDownLatch( count );

        Logger logger = LoggerFactory.getLogger( getClass() );
        ExecutorService executor = Executors.newCachedThreadPool();
        for( int i=0; i<count; i++)
        {
            logger.info( "Starting: {}", i );
            executor.execute( () -> {
                logger.info( "Signaling thread: {} has started", Thread.currentThread().getName() );
                start.countDown();
                try
                {
                    logger.info( "Waiting for other thread(s) to start..." );
                    start.await( 3, TimeUnit.SECONDS );

                    assertThat( "Threads did not start correctly!", start.getCount(), equalTo( 0L ) );

                    logger.info( "Opening: {}", f );

                    try (InputStream in = mgr.openInputStream( f ))
                    {
                        assertThat( IOUtils.toString( in ), equalTo( src ) );
                    }
                    catch ( IOException e )
                    {
                        e.printStackTrace();
                        fail( "Cannot open: " + f );
                    }
                }
                catch ( InterruptedException e )
                {
                    e.printStackTrace();
                    fail( "Interrupted" );
                }
                finally
                {
                    logger.info( "Signaling thread: {} has ended", Thread.currentThread().getName() );
                    end.countDown();
                }
            } );
        }

        logger.info( "Waiting for end of threads" );
        end.await(5, TimeUnit.SECONDS);

        assertThat( "Threads did not end correctly!", end.getCount(), equalTo( 0L ) );

        AtomicInteger counter = new AtomicInteger( 0 );
        mgr.getFileTree().forAll( entry->true, entry->counter.incrementAndGet() );

        assertThat( "FileEntry instance was not removed after closing!", counter.get(), equalTo( 0 ) );
    }

    @Test
    public void concurrentWriteAndRead_CleanupFileEntryOnLastClose()
            throws Exception
    {
        String src = "This is a test";

        File f = temp.newFile();
        FileUtils.write( f, src );

        int count = 2;
        CountDownLatch writing = new CountDownLatch( count );
        CountDownLatch reading = new CountDownLatch( count );
        CountDownLatch end = new CountDownLatch( count );

        Logger logger = LoggerFactory.getLogger( getClass() );
        ExecutorService executor = Executors.newCachedThreadPool();
        executor.execute( ()->{
            logger.info( "Starting write: {}", f );
            try (OutputStream out = mgr.openOutputStream( f ))
            {
                logger.info( "Signaling write starting: {}", f );
                writing.countDown();

                IOUtils.write( src, out );

                logger.info( "Waiting for read to start..." );
                reading.await(1, TimeUnit.SECONDS);
            }
            catch ( IOException e )
            {
                e.printStackTrace();
                fail( "Failed to write: " + f );
            }
            catch ( InterruptedException e )
            {
                e.printStackTrace();
                fail( "Write Interrupted!" );
            }
            finally
            {
                end.countDown();
            }
        } );

        executor.execute( () -> {
            logger.info( "Signaling thread: {} has started", Thread.currentThread().getName() );
            writing.countDown();
            try
            {
                logger.info( "Waiting for other thread(s) to written..." );
                writing.await( 1, TimeUnit.SECONDS );

                assertThat( "Threads did not written correctly!", writing.getCount(), equalTo( 0L ) );

                logger.info( "Opening: {}", f );

                try (InputStream in = mgr.openInputStream( f ))
                {
                    logger.info( "Signaling that reading has begun: {}", f );
                    reading.countDown();
                    assertThat( IOUtils.toString( in ), equalTo( src ) );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "Cannot open: " + f );
                }
            }
            catch ( InterruptedException e )
            {
                e.printStackTrace();
                fail( "Interrupted" );
            }
            finally
            {
                logger.info( "Signaling thread: {} has ended", Thread.currentThread().getName() );
                end.countDown();
            }
        } );

        logger.info( "Waiting for end of threads" );
        end.await(5, TimeUnit.SECONDS);

        assertThat( "Threads did not end correctly!", end.getCount(), equalTo( 0L ) );

        AtomicInteger counter = new AtomicInteger( 0 );
        mgr.getFileTree().forAll( entry->true, entry->counter.incrementAndGet() );

        assertThat( "FileEntry instance was not removed after closing!", counter.get(), equalTo( 0 ) );
    }

    @Test
    public void lockWriteDoesntPreventOpenInputStream()
            throws Exception
    {
        String src = "This is a test";

        File f = temp.newFile();
        FileUtils.write( f, src );

        assertThat( "Write lock failed.", mgr.lock( f, -1, LockLevel.write, "test" ), equalTo( true ) );

        InputStream stream = mgr.openInputStream( f );
        assertThat( "InputStream cannot be null", stream, notNullValue() );

        String result = IOUtils.toString( stream );
        assertThat( result, equalTo( src ) );
    }

    @Test
    public void waitForLockThenOpenOutputStream()
            throws Exception
    {
        final File f = temp.newFile();
        assertThat( mgr.waitForWriteUnlock( f ), equalTo( true ) );

        try(OutputStream stream = mgr.openOutputStream( f ))
        {
            //nop
        }

        assertThat( mgr.isWriteLocked( f ), equalTo( false ) );
    }

    @Test
    public void openOutputStream_VerifyWriteLocked_NotReadLocked()
        throws Exception
    {
        final File f = temp.newFile();
        final OutputStream stream = mgr.openOutputStream( f );

        assertThat( mgr.isWriteLocked( f ), equalTo( true ) );
        assertThat( mgr.isReadLocked( f ), equalTo( false ) );

        stream.close();

        assertThat( mgr.isWriteLocked( f ), equalTo( false ) );
        assertThat( mgr.isReadLocked( f ), equalTo( false ) );
    }

    @Test( expected = IOException.class )
    public void openOutputStream_TimeBoxedSecondCallThrowsException()
            throws Exception
    {
        ThreadContext ctx = ThreadContext.getContext( true );
        final String key = "real owner";

        final File f = temp.newFile();

        Thread.currentThread().setName( key );
        final OutputStream stream = mgr.openOutputStream( f );

        assertThat( ((String)ctx.get( PARTYLINE_LOCK_OWNER )).contains(key), equalTo( true ) );

        ctx.put( PARTYLINE_LOCK_OWNER, "output 2" );
        OutputStream s2 = mgr.openOutputStream( f, SHORT_TIMEOUT );

        assertThat( s2, nullValue() );

        ctx.put( PARTYLINE_LOCK_OWNER, key );
        stream.close();

        ctx.put( PARTYLINE_LOCK_OWNER, "output 3" );
        s2 = mgr.openOutputStream( f, SHORT_TIMEOUT );

        assertThat( s2, notNullValue() );
    }

    @Test
    public void openInputStream_VerifyWriteLocked_ReadLocked()
        throws Exception
    {
        final File f = temp.newFile();
        final InputStream stream = mgr.openInputStream( f );

        assertThat( mgr.isWriteLocked( f ), equalTo( true ) );

        // after change to always use JoinableFile (even for read-first), I don't think partyline will ever read lock.
        assertThat( mgr.isReadLocked( f ), equalTo( false ) );

        stream.close();

        assertThat( mgr.isWriteLocked( f ), equalTo( false ) );
        assertThat( mgr.isReadLocked( f ), equalTo( false ) );
    }

    @Test
    @Ignore( "With change to JoinableFile for all reads, partyline should never read-lock" )
    public void openInputStream_TimeBoxedSecondCallReturnsNull()
        throws Exception
    {
        final File f = temp.newFile();
        final InputStream stream = mgr.openInputStream( f );

        InputStream s2 = mgr.openInputStream( f, SHORT_TIMEOUT );

        assertThat( s2, nullValue() );

        stream.close();

        s2 = mgr.openInputStream( f, SHORT_TIMEOUT );

        assertThat( s2, notNullValue() );
    }

    @Test
    public void openInputStream_cleanupCurrentThread_openOutputStream()
        throws Exception
    {
        ThreadContext.getContext( true );

        final File f = temp.newFile("test.txt");
        FileUtils.write( f, "This is first pass" );
        mgr.openInputStream( f );
        mgr.cleanupCurrentThread();
        OutputStream outputStream = mgr.openOutputStream( f );

        outputStream.close();
        ThreadContext.clearContext();
    }

}
