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

package org.walog.rmi;

import org.walog.SimpleWal;
import org.walog.WalException;
import org.walog.WalIterator;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

public class WalIteratorWrapper extends UnicastRemoteObject implements RmiIteratorWrapper {

    protected final WalIterator iterator;

    public WalIteratorWrapper(WalIterator iterator) throws RemoteException {
        this.iterator = iterator;
    }

    @Override
    public boolean hasNext() throws WalException, RemoteException {
        return this.iterator.hasNext();
    }

    @Override
    public SimpleWal next() throws WalException, RemoteException {
        return (SimpleWal)this.iterator.next();
    }

    @Override
    public void remove() throws RemoteException {
        throw new UnsupportedOperationException("wal iterator read only");
    }

    @Override
    public boolean isOpen() throws RemoteException {
        return this.iterator.isOpen();
    }

    @Override
    public void close() throws RemoteException {
        this.iterator.close();
    }

}
