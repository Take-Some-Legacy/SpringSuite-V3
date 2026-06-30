package com.takesome.springsuite.workspace;

import java.util.ArrayList;
import java.util.List;

public class WorkspaceProfileProperties {
    private List<String> roots = new ArrayList<>();
    private Boolean allowRead;
    private Boolean allowWrite;
    private Boolean allowDelete;
    private List<String> denySegments = new ArrayList<>();
    private List<String> denyGlobs = new ArrayList<>();
    public List<String> getRoots() { return roots; }
    public void setRoots(List<String> roots) { this.roots = roots == null ? new ArrayList<>() : new ArrayList<>(roots); }
    public Boolean getAllowRead() { return allowRead; }
    public void setAllowRead(Boolean allowRead) { this.allowRead = allowRead; }
    public Boolean getAllowWrite() { return allowWrite; }
    public void setAllowWrite(Boolean allowWrite) { this.allowWrite = allowWrite; }
    public Boolean getAllowDelete() { return allowDelete; }
    public void setAllowDelete(Boolean allowDelete) { this.allowDelete = allowDelete; }
    public List<String> getDenySegments() { return denySegments; }
    public void setDenySegments(List<String> denySegments) { this.denySegments = denySegments == null ? new ArrayList<>() : new ArrayList<>(denySegments); }
    public List<String> getDenyGlobs() { return denyGlobs; }
    public void setDenyGlobs(List<String> denyGlobs) { this.denyGlobs = denyGlobs == null ? new ArrayList<>() : new ArrayList<>(denyGlobs); }
}
