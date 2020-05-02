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

package org.walog.internal;

import org.walog.WalIterator;
import org.walog.util.IoUtils;
import org.walog.util.WalFileUtils;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.util.NoSuchElementException;

class NioWalIterator implements WalIterator {

    static final long LSN_UNDEFINED = -1L;
    static final long NOT_TIMEOUT = -1L;

    protected final NioWaler waler;
    protected long lsn;
    protected final long timeout;
    protected NioWalFile walFile;
    protected SimpleWal wal, last;
    private boolean hasNextCalled;
    private boolean noNext;
    private boolean open = true;

    public NioWalIterator(NioWaler waler) {
        this.waler = waler;
        this.lsn   = LSN_UNDEFINED;
        this.timeout = NOT_TIMEOUT;
    }

    public NioWalIterator(NioWaler waler, long lsn)
            throws IllegalArgumentException {
        this(waler, lsn, NOT_TIMEOUT);
    }

    public NioWalIterator(NioWaler waler, long lsn, long timeout)
            throws IllegalArgumentException {
        NioWaler.checkLsn(lsn);
        this.waler = waler;
        this.lsn   = lsn;
        this.timeout = timeout;
    }

    @Override
    public boolean hasNext() {
        this.hasNextCalled = true;
        if (this.noNext) {
            return false;
        }
        if (this.wal != null) {
            return true;
        }
        if (!this.open) {
            throw new IllegalStateException("Wal iterator closed");
        }

        boolean failed = false;
        try {
            if (this.timeout >= 0L) {
                // timeout version
                if (this.last != null) {
                    this.wal = this.waler.next(this.last, timeout);
                } else if (this.lsn != LSN_UNDEFINED) {
                    this.wal = this.waler.get(this.lsn);
                } else {
                    this.wal = this.waler.first(timeout);
                }
                if (this.wal == null) {
                    this.noNext = true;
                    return false;
                }
                this.lsn = this.wal.nextLsn();
                return true;
            }

            failed = true;
            if (this.walFile == null) {
                // 1. Initialize lsn if undefined
                if (this.lsn == LSN_UNDEFINED) {
                    this.wal = this.waler.first();
                    if (this.wal == null) {
                        this.noNext = true;
                        failed = false;
                        return false;
                    }
                    this.lsn = this.wal.getLsn();
                }
                // 2. Initialize wal file
                this.walFile = this.waler.getWalFile(this.lsn);
                if (this.walFile == null) {
                    this.noNext = true;
                    failed = false;
                    return false;
                }
                if (this.wal != null) {
                    this.lsn = this.wal.nextLsn();
                    failed = false;
                    return true;
                }
            }

            this.wal = this.walFile.get(this.lsn);
            if (this.wal == null) {
                this.walFile.release();
                // Open next wal file
                this.lsn = WalFileUtils.nextFileLsn(this.lsn);
                this.walFile = this.waler.getWalFile(this.lsn);
                if (this.walFile == null) {
                    this.noNext = true;
                    failed = false;
                    return false;
                }
                this.wal = this.walFile.get(this.lsn);
            }

            if (this.wal != null) {
                this.lsn = this.wal.nextLsn();
                failed = false;
                return true;
            }

            this.noNext = true;
            failed = false;
            return false;
        } catch (EOFException e) {
            if (this.walFile.isLastFile()) {
                File file = this.walFile.getFile();
                IoUtils.debug("Reach to the end of file '%s'", file);
                this.noNext = true;
                failed = false;
                return false;
            }
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (this.noNext || failed) {
                close();
            }
        }
    }

    @Override
    public SimpleWal next() {
        if (!this.hasNextCalled) {
            throw new IllegalStateException("haxNext() not called");
        }
        this.hasNextCalled = false;
        if (this.noNext) {
            throw new NoSuchElementException();
        }

        this.last = this.wal;
        this.wal = null;
        return this.last;
    }

    @Override
    public boolean isOpen() {
        return this.open;
    }

    @Override
    public void close() {
        if (this.walFile != null) {
            this.walFile.release();
        }
        this.noNext = true;
        this.open = false;
    }

}
