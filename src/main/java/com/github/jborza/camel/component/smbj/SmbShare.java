/**
 *  Copyright [2018] [Juraj Borza]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.jborza.camel.component.smbj;

import com.github.jborza.camel.component.smbj.dfs.DfsResolutionResult;
import com.github.jborza.camel.component.smbj.dfs.DfsResolver;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.common.SmbPath;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import org.apache.camel.component.file.GenericFileOperationFailedException;
import org.apache.camel.util.IOHelper;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static com.github.jborza.camel.component.smbj.SmbConstants.CURRENT_DIRECTORY;
import static com.github.jborza.camel.component.smbj.SmbConstants.PARENT_DIRECTORY;

public class SmbShare implements AutoCloseable {
    private final SMBClient client;
    private final SmbConfiguration config;
    private final boolean dfs;
    private final int bufferSize;

    private Session session;
    private String path;
    private DiskShare share;

    public SmbShare(SMBClient client, SmbConfiguration config, boolean dfs, int bufferSize) {
        this.client = client;
        this.config = config;
        this.dfs = dfs;
        this.bufferSize = bufferSize;
    }

    private void connect(String targetPath) {
        session = connectSession(config.getHost());
        DfsResolutionResult pathResolutionResult = resolvePlainPath(targetPath);
        path = pathResolutionResult.getSmbPath().getPath();
        share = pathResolutionResult.getDiskShare();
    }

    private DfsResolutionResult resolvePlainPath(String targetPath) {
        String actualPath = SmbPathUtils.removeShareName(SmbPathUtils.convertToBackslashes(targetPath), config.getShare(), true);
        SmbPath targetSmbPath = new SmbPath(config.getHost(), config.getShare(), actualPath);
        return resolvePath(session, targetSmbPath);
    }

    @Override
    public void close() throws IOException {
        if (share != null)
            share.close();
        if (session != null)
            session.close();
    }

    /**
     * Gets the connected share (either resolved by DFS or directly connected)
     */
    public DiskShare getShare() {
        return share;
    }

    /**
     * Gets the DFS resolved path, if DFS is used. Otherwise the supplied path is returned
     */
    public String getPath() {
        return path;
    }

    private boolean isDfs() {
        return dfs;
    }

    private Session connectSession(String host) {
        try {
            Connection connection = client.connect(host);
            return connection.authenticate(getAuthenticationContext());
        } catch (IOException e) {
            throw new SmbConnectionException(e);
        }
    }

    private AuthenticationContext getAuthenticationContext() {
        String username = config.getUsername();
        String domain = config.getDomain();
        String password = config.getPassword();
        if (username == null)
            throw new IllegalArgumentException("Username cannot be null!");
        if (password == null)
            throw new IllegalArgumentException("Password cannot be null!");
        return new AuthenticationContext(username, password.toCharArray(), domain);
    }

    private DfsResolutionResult resolvePath(Session session, SmbPath path) {
        if (isDfs()) {
            return connectDfsShare(session, path);
        } else {
            return connectNonDfsShare(session, path);
        }
    }

    private DfsResolutionResult connectDfsShare(Session session, SmbPath path) {
        DfsResolver resolver = new DfsResolver();
        SmbPath resolvedPath = resolver.resolve(client, session, path);
        DiskShare share = getDfsShare(session, resolvedPath);
        return new DfsResolutionResult(share, resolvedPath);
    }

    private DfsResolutionResult connectNonDfsShare(Session session, SmbPath path) {
        DiskShare share = (DiskShare) session.connectShare(path.getShareName());
        return new DfsResolutionResult(share, path);
    }

    private DiskShare getDfsShare(Session session, SmbPath resolvedPath) {
        if (isOnSameHost(session, resolvedPath))
            return (DiskShare) session.connectShare(resolvedPath.getShareName());
        else {
            Session newSession = connectSession(resolvedPath.getHostname());
            return (DiskShare) newSession.connectShare(resolvedPath.getShareName());
        }
    }

    private boolean isOnSameHost(Session session, SmbPath path) {
        return session.getConnection().getRemoteHostname().equals(path.getHostname());
    }

    public void rename(String from, String to) {
        session = connectSession(config.getHost());
        DfsResolutionResult resolvedFrom = resolvePlainPath(from);
        DfsResolutionResult resolvedTo = resolvePlainPath(to);
        if (!resolvedFrom.getSmbPath().isOnSameShare(resolvedTo.getSmbPath())) {
            //TODO introduce a specialized exception type
            throw new GenericFileOperationFailedException("Rename operation failed, " + from + " and " + to + " are on different shares!");
        }
        DiskShare share = resolvedFrom.getDiskShare();
        EnumSet<AccessMask> renameAttributes = EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.DELETE, AccessMask.SYNCHRONIZE);
        File file = share.openFile(resolvedFrom.getSmbPath().getPath(), renameAttributes, null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
        file.rename(resolvedTo.getSmbPath().getPath());
    }

    public void storeFile(String path, InputStream inputStream) throws IOException {
        connect(path);
        File file = openForWrite(getShare(), getPath());
        try (OutputStream outputStream = file.getOutputStream()) {
            IOUtils.copy(inputStream, outputStream, bufferSize);
        }
    }

    public List<SmbFile> listFiles(String path) {
        connect(path);
        List<SmbFile> files = new ArrayList<>();
        for (FileIdBothDirectoryInformation f : getShare().list(getPath())) {
            boolean isDirectory = FileDirectoryAttributes.isDirectory(f);
            if (isDirectory) {
                //skip special directories . and ..
                if (f.getFileName().equals(CURRENT_DIRECTORY) || f.getFileName().equals(PARENT_DIRECTORY))
                    continue;
            }
            files.add(new SmbFile(isDirectory, f.getFileName(), f.getEndOfFile(), FileDirectoryAttributes.getLastModified(f)));
        }
        return files;
    }

    public void retrieveFile(String path, OutputStream os) throws IOException {
        connect(path);
        File f = openForRead(getShare(), getPath());
        InputStream is = f.getInputStream();
        IOHelper.copyAndCloseInput(is, os, bufferSize);
    }

    public boolean fileExists(String path) throws IOException {
        connect(path);
        return getShare().fileExists(getPath());
    }

    public void deleteFile(String path) throws IOException {
        connect(path);
        getShare().rm(getPath());
    }

    public boolean mkdirs(String directory) {
        connect(directory);
        Path path = Paths.get(getPath());
        mkdirs(path);
        return true;
    }

    private void mkdirs(Path path){
        if(!getShare().folderExists(path.getParent().toString()))
            mkdirs(path.getParent());
        getShare().mkdir(path.toString());
    }

    private static File openForWrite(DiskShare share, String name) {
        return share.openFile(name, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE, EnumSet.of(SMB2CreateOptions.FILE_SEQUENTIAL_ONLY));
    }

    private static File openForRead(DiskShare share, String name) {
        //NB https://msdn.microsoft.com/en-us/library/cc246502.aspx - SMB2 CREATE Request
        // ShareAccess.ALL means that other opens are allowed to read, but not write or delete the file
        return share.openFile(name, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
    }

}
