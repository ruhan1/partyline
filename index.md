---
---

### Why Wait?

Partyline is a library for providing read access to files that are still being written. This is particularly useful for server applications (like [AProx](/aprox/)) which cache remote files and provide them to users on demand. Without joinable I/O, such users must wait for the file to cache fully before it it served to them. With joinable I/O, the user starts receiving data as soon as the server application starts writing it to the cache file. Even users that request the file after the original cache-triggering event, but before the file caches completely, can join the I/O stream and \"catch up\" to the current download progress.

While this logic may not seem so mysterious to some developers, it's useful to have a tested library for this functionality to make it reusable with minimal fuss or risk of threading error.

### Like `OutputStream`, but Fancier!

The core of Partyline is the `JoinableOutputStream`, which is a fancy implementation of `OutputStream` that wraps a `RandomAccessFile` and a set of synchronized `InputStream` implementations. As data is written, it fills an internal buffer. When the buffer fills, the `flush()` method is called, and the stream writes the data buffer to the `RandomAccessFile` along with any waiting, synchronized `InputStream` instances that it tracks. When the `close()` method of the stream is called, it waits for any synchronized `InputStream`s to finish reading (or close), then closes down the underlying `RandomAccessFile`. The byte count written to the `RandomAccessFile` is stored to prevent synchronized streams from reading beyond the data written when joining a stream already in progress.

Joining a stream (obtaining an `InputStream` from an existing `JoinableOutputStream`) is simple. Just call the `joinStream()` method on the existing output stream instance!

### Keeping Your ~~Ducks~~Streams in a Row

Obviously, having an `OutputStream` that is joinable for multiple readers is useful. However, if you're implementing a server application you don't really want to have to manage all those streams just so you can join them for reads. But if you don't, you can't really use joinable I/O at all!

Relax, that's where `JoinableFileManager` comes in. This file manager tracks read and write locks on a `File` by `File` basis, whether they're manual locks, locks created without join support (read before write starts), or joinable locks (write locks that allow concurrent reads). It also supports waiting for a lock to become available.

The following conditions are managed:

#### First Writer

If a user calls `openOutputStream(..)` and no other stream is reading or writing to that file, a new `JoinableOutputStream` is created and passed back to the user. The file is locked for writing at this point, but new readers are allowed.

#### First Reader

If a user calls `openInputStream(..)` and no other stream is reading or writing that file, a new `FileInputStream` is created and passed back to the user. The file is locked for writing and reading at this point, because the stream is not joinable, and its content must be preserved while the user is reading it.

#### Second Writer

If a user calls `openOutputStream(..)` and an existing output stream is already open for that file, the new call will wait for the write lock to become available. This method has two forms: one that waits indefinitely, and another that waits for a specified millisecond timeout before returning null. No exception is thrown because a timeout due to a long usage time by the first writer is not an exceptional case.

#### First Reader with Existing Writer

If a user calls `openInputStream(..)` and an existing output stream is already open for that file, the new call will retrieve the `JoinableOutputStream` for the file and call `joinStream()` to obtain a new `InputStream` to return to the caller.

#### Second Reader

Calling 
`openInputStream(..)` for a file that has an existing read lock but no write lock (this is not a joinable stream) results in behavior similar to the second call to `openOutputStream(..)`, described above. The method has two variations, one that waits indefinitely for the first read stream to close, and the second that will wait a specified number of milliseconds before giving up and returning null. This is not considered an exceptional case, so null is used rather than throwing an exception.

#### Manual Lock / Unlock

Users can instead notify the `JoinableFileManager` that they are working with the file outside of the file manager's ability to track it. Sometimes this is useful when integrating with other libraries that construct and use `File` instances from some path calculation, for example.

If this happens, the application can call the `lock(..)` method to prevent any read or write activity from happening via the file manager. When the external logic completes, the application must call the `unlock(..)` method to remove the read and write locks and allow normal access again.

#### Waiting for Lock Availability

The application can synchronize on file accesses that are mediated by the `JoinableFileManager` through one of the `waitFor*Unlock(..)` methods (`waitForReadUnlock(..)` and `waitForWriteUnlock(..)`). As above, these methods have variations that allow indefinite waiting, or waiting for a specified millisecond timeout. They return a `boolean` denoting whether the lock is available. If `false`, the method timed out.

### We'll Call You (Back)

Sometimes it's critical for an application to perform some sort of clean-up action when a file is closed. For instance, servers that cache content often want to download to a temporary file then rename it when the download completes, to prevent the possibility of serving partial content as if it were a complete file. 

Partyline supports this through its `CallbackInputStream` and `CallbackOutputStream` wrappers. Each of these has a constructor that accepts the appropriate type of stream (to wrap), and an instance of `StreamCallbacks`. Partyline also implements an abstract form of `StreamCallbacks` (`AbstractStreamCallbacks`), which provides default / null implementations of the methods, so the user only has to implement desired methods.

`StreamCallbacks` has two methods: `flushed()` and `closed()`. Obviously, when used in `CallbackInputStream` only the `closed()` method is actually used.