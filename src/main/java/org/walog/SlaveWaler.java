/**
 * The MIT License (MIT)
 * <p>
 * Copyright (c) 2020 little-pan
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.walog;

import org.walog.internal.NioWaler;
import org.walog.util.IoUtils;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import static java.lang.Integer.*;

public class SlaveWaler implements Waler {

    static final int WAIT_TIMEOUT = getInteger("org.walog.slave.waitTimeout", 1000);
    static final int RECON_PERIOD = getInteger("org.walog.slave.reconnectPeriod", 100);

    public static final int STATE_INIT = 0x00;
    public static final int STATE_WAIT = 0x01;
    public static final int STATE_APPENDING = 0x02;
    public static final int STATE_FAILED    = 0x04;
    public static final int STATE_CLOSED    = 0x08;
    public static final int STATE_CONNECT   = 0x10;

    protected volatile Waler waler;
    protected final String dataDir;
    protected final Properties props;

    protected volatile Waler master;
    protected final String masterURL;
    protected final Properties masterInfo;

    protected volatile Replicator replicator;
    private volatile int state = STATE_INIT;

    public SlaveWaler(String dataDir, Properties props,
                      Waler master, String masterURL, Properties masterInfo) {
        this.master  = master;
        this.dataDir = dataDir;
        this.props   = props;
        this.masterURL = masterURL;
        this.masterInfo = masterInfo;
    }

    public Waler getMaster() {
        return this.master;
    }

    public int getState() {
        return this.state;
    }

    public long bytesBehindMaster() {
        Replicator replicator = this.replicator;
        if (replicator == null) {
            return -1L;
        } else {
            return this.replicator.bytesBehindMaster();
        }
    }

    @Override
    public synchronized void open() throws WalException {
        if (this.waler != null) {
            throw new IllegalStateException("slave waler opened");
        }

        AppendOptions options = AppendOptions.builder()
                .asyncMode(0).flushUnlock(false).build();
        // Fetch options
        int fetchSize = 0;
        boolean fetchLast = false;
        String value = this.props.getProperty("fetchSize");
        if (value != null) {
            fetchSize = Integer.decode(value);
        }
        value = this.props.getProperty("fetchLast");
        if (value != null) {
            fetchLast = Boolean.parseBoolean(value);
        }
        this.waler  = WalerFactory.open(this.dataDir, options, fetchSize, fetchLast);
        Wal lastWal = this.waler.last();
        IoUtils.debug("slave last wal: %s", lastWal);

        // Start replicator
        this.replicator = new Replicator(this, lastWal);
        replicator.start();
    }

    @Override
    public Wal append(byte[] log) throws WalException {
        throw new WalException("Slave is read only");
    }

    @Override
    public Wal append(byte[] log, int offset, int length) throws WalException {
        throw new WalException("Slave is read only");
    }

    @Override
    public Wal append(String log) throws WalException {
        throw new WalException("Slave is read only");
    }

    @Override
    public Wal first() throws WalException {
        ensureOpen();
        return this.waler.first();
    }

    @Override
    public Wal first(long timeout) throws WalException {
        ensureOpen();
        return this.waler.first(timeout);
    }

    @Override
    public Wal get(long lsn) throws WalException, IllegalArgumentException {
        ensureOpen();
        return this.waler.get(lsn);
    }

    @Override
    public Wal next(Wal wal) throws WalException, IllegalArgumentException {
        ensureOpen();
        return this.waler.next(wal);
    }

    @Override
    public Wal next(Wal wal, long timeout) throws WalException, IllegalArgumentException {
        ensureOpen();
        return this.waler.next(wal, timeout);
    }

    @Override
    public WalIterator iterator() {
        ensureOpen();
        return this.waler.iterator();
    }

    @Override
    public WalIterator iterator(long lsn) throws IllegalArgumentException {
        ensureOpen();
        return this.waler.iterator(lsn);
    }

    @Override
    public WalIterator iterator(long lsn, long timeout) throws IllegalArgumentException {
        ensureOpen();
        return this.waler.iterator(lsn, timeout);
    }

    @Override
    public boolean purgeTo(String filename) throws WalException {
        ensureOpen();
        return this.waler.purgeTo(filename);
    }

    @Override
    public boolean purgeTo(long fileLsn) throws WalException {
        ensureOpen();
        return this.waler.purgeTo(fileLsn);
    }

    @Override
    public boolean clear() throws WalException {
        ensureOpen();
        return this.waler.clear();
    }

    @Override
    public void sync() throws WalException {
        ensureOpen();
        this.waler.sync();
    }

    @Override
    public Wal last() throws WalException {
        ensureOpen();
        return this.waler.last();
    }

    @Override
    public boolean isOpen() {
        Waler waler = this.waler;
        return (waler != null && waler.isOpen());
    }

    @Override
    public void close() {
        IoUtils.close(this.waler);
        IoUtils.close(this.master);
        // Wait replicator
        Replicator replicator = this.replicator;
        if (replicator != null) {
            try {
                replicator.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // INT
            }
        }
    }

    protected void ensureOpen() throws WalException {
        if (!isOpen()) {
            throw new IOWalException("slave waler not open");
        }
    }

    static class Replicator extends Thread {
        static final AtomicLong ID = new AtomicLong();

        protected final SlaveWaler slave;
        protected volatile Wal curr, last;
        private boolean appended;
        private long syncTime;

        public Replicator(SlaveWaler slave, Wal curr) {
            this.slave = slave;
            this.curr = curr;
            setName("replicator-" + ID.getAndIncrement());
            setDaemon(true);
        }

        public long bytesBehindMaster() {
            Wal curr = this.curr, last = this.last;
            if (curr == null) {
                return -1L;
            } else {
                return (last.getLsn() - curr.getLsn());
            }
        }

        @Override
        public void run() {
            SlaveWaler slave = this.slave;
            NioWaler waler = (NioWaler)slave.waler;
            Waler master = slave.getMaster();

            slave.state = STATE_WAIT;
            WalIterator it = null;
            long timeout = WAIT_TIMEOUT;
            boolean failed = true;
            try {
                while (true) {
                    try {
                        // Init "from wal"
                        Wal fromWal = this.curr;
                        while (slave.isOpen()) {
                            try {
                                if (fromWal == null) {
                                    fromWal = master.first(timeout);
                                } else {
                                    long lsn = fromWal.getLsn();
                                    it = master.iterator(lsn, timeout);
                                    this.last = master.last();
                                    break;
                                }
                            } catch (TimeoutWalException e) {
                                tryLog(e);
                            }
                        }

                        while (slave.isOpen()) {
                            slave.state = STATE_WAIT;
                            try {
                                assert it != null;
                                while (it.hasNext()) {
                                    final SimpleWal wal = (SimpleWal)it.next();
                                    final Wal curr = this.curr;

                                    if (curr == null) {
                                        assert fromWal != null;
                                        if (fromWal.getLsn() != wal.getLsn()) {
                                            throw new WalException("First wal from master not matched");
                                        }
                                        fromWal = null;
                                    }
                                    if (curr == null || curr.getLsn() != wal.getLsn()) {
                                        slave.state = STATE_APPENDING;
                                        waler.append(wal);
                                        this.appended = true;
                                        trySync(waler);
                                    }
                                    slave.state = STATE_WAIT;
                                    this.curr = wal;
                                    final Wal last = this.curr.getLast();
                                    if (last == null) {
                                        this.last = master.last();
                                    } else {
                                        this.last = last;
                                    }
                                }
                                trySync(waler);
                            } catch (TimeoutWalException e) {
                                tryLog(e);
                            }
                        }
                        slave.state = STATE_CLOSED;
                        failed = false;
                        return;
                    } catch (WalException e) {
                        IoUtils.close(it);
                        if (slave.isOpen()) {
                            try {
                                if (master.isOpen() && !(e instanceof NetWalException)) {
                                    throw e;
                                }
                            } catch (NetWalException ignore) {
                                // Ignore remote call exception
                            }
                            slave.state = STATE_FAILED;
                            IoUtils.close(master);
                            slave.state = STATE_CONNECT;
                            master = reconnect();
                        } else {
                            slave.state = STATE_CLOSED;
                            failed = false;
                            return;
                        }
                    }
                } // while
            } catch (WalException e) {
                if (slave.isOpen()) {
                    throw e;
                }
            } finally {
                if (failed) {
                    slave.state = STATE_FAILED;
                }
                IoUtils.close(it);
            }
        }

        Waler reconnect() throws WalException {
            long reconPeriod = RECON_PERIOD;
            SlaveWaler slave = this.slave;

            while (slave.isOpen()) {
                final Waler master;
                try {
                    master = WalDriverManager.connect(slave.masterURL, slave.masterInfo);
                    if (master == null) {
                        String s = "No wal driver found for master url: " + slave.masterURL;
                        throw new WalException(s);
                    }
                    return (slave.master = master);
                } catch (WalException e) {
                    if (!(e instanceof NetWalException)) {
                        throw e;
                    }
                    try {
                        if (reconPeriod >= 0) {
                            Thread.sleep(reconPeriod);
                        }
                    } catch (InterruptedException ie) {
                        throw new InterruptedWalException(ie);
                    }
                }
            }

            throw new WalException("Slave waler closed");
        }

        void tryLog(TimeoutWalException e) {
            long diff = bytesBehindMaster();
            if (diff != -1 && diff != 0) {
                String s = "replicate timeout: lsn diff " + diff;
                if (STATE_WAIT == this.slave.getState()) {
                   s  = "wait timeout: lsn diff " + diff;
                }
                IoUtils.error(s, e);
            }
        }

        void trySync(Waler waler) throws WalException {
            if (this.appended && isAutoFlush() && isFlushTime()) {
                waler.sync();
                this.appended = false;
                this.syncTime = System.currentTimeMillis();
            }
        }

        boolean isFlushTime() {
            long flushPeriod = AppendOptions.FLUSH_PERIOD;
            return (flushPeriod <= 0 || System.currentTimeMillis() - this.syncTime >= flushPeriod);
        }

    }

    static boolean isAutoFlush() {
        return (AppendOptions.AUTO_FLUSH == 1);
    }

}
