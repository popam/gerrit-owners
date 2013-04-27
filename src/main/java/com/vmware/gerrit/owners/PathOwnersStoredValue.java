/*
 * Copyright (c) 2013 VMware, Inc. All Rights Reserved.
 */
package com.vmware.gerrit.owners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Patch;
import com.google.gerrit.rules.PrologEnvironment;
import com.google.gerrit.rules.StoredValue;
import com.google.gerrit.rules.StoredValues;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.patch.PatchList;
import com.google.gerrit.server.patch.PatchListEntry;
import com.google.gwtorm.server.OrmException;
import com.googlecode.prolog_cafe.lang.Prolog;
import com.googlecode.prolog_cafe.lang.SystemException;
import org.eclipse.jgit.lib.Repository;
import org.gitective.core.BlobUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Path Owners Stored Value.
 * <p/>
 * Cached by the Prolog Engine across rule evaluation. Provides a mapping of OWNERS file referenced by the commit to
 * a set of emails contained in the OWNERS file.
 */
public class PathOwnersStoredValue extends StoredValue<SetMultimap<String, Account.Id>> {
  private static final Logger log = LoggerFactory.getLogger(IdentifiedUser.class);

  // Cached path owners.
  public static PathOwnersStoredValue VALUE = new PathOwnersStoredValue();

  @Override
  protected SetMultimap<String, Account.Id> createValue(Prolog engine) {
    PatchList patchList = StoredValues.PATCH_LIST.get(engine);
    List<PatchListEntry> patches = patchList.getPatches();
    Set<String> paths = new HashSet<String>();
    for (PatchListEntry patch : patches) {
      // Ignore commit message
      if (!patch.getNewName().equals("/COMMIT_MSG")) {
        paths.add(patch.getNewName());

        // If a file was moved then we need approvals for old and new path
        if (patch.getChangeType() == Patch.ChangeType.RENAMED) {
          paths.add(patch.getOldName());
        }
      }
    }

    PrologEnvironment env = (PrologEnvironment) engine.control;
    AccountResolver resolver = env.getInjector().getInstance(AccountResolver.class);

    Repository repository = StoredValues.REPOSITORY.get(engine);
    return getOwners(repository, resolver, paths);
  }

  private SetMultimap<String, Account.Id> getOwners(Repository repository,
                                                    AccountResolver resolver,
                                                    Set<String> paths) {

    SetMultimap<String, Account.Id> result = HashMultimap.create();
    Map<String, PathOwnersEntry> entries = new HashMap<String, PathOwnersEntry>();

    PathOwnersEntry rootEntry = new PathOwnersEntry();
    OwnersConfig rootConfig = getOwners(repository, "OWNERS");
    if (rootConfig != null) {
      rootEntry.setOwnersPath("OWNERS");
      rootEntry.addOwners(getOwnersFromEmails(resolver, rootConfig.getOwners()));
    }

    for (String path : paths) {
      String[] parts = path.split("/");

      PathOwnersEntry currentEntry = rootEntry;
      StringBuilder builder = new StringBuilder();

      // Iterate through the parent paths, not including the file name itself
      for (int i = 0, partsLength = parts.length - 1; i < partsLength; i++) {
        String part = parts[i];
        builder.append(part).append("/");
        String partial = builder.toString();

        // Skip if we already parsed this path
        if (!entries.containsKey(partial)) {
          String ownersPath = partial + "OWNERS";
          OwnersConfig config = getOwners(repository, ownersPath);
          if (config != null) {
            PathOwnersEntry entry = new PathOwnersEntry();
            entry.setOwnersPath(ownersPath);
            entry.addOwners(getOwnersFromEmails(resolver, config.getOwners()));

            if (config.isInherited()) {
              entry.addOwners(currentEntry.getOwners());
            }

            currentEntry = entry;
          }

          entries.put(partial, currentEntry);
        } else {
          currentEntry = entries.get(partial);
        }
      }

      result.putAll(path, currentEntry.getOwners());
    }

    return result;
  }

  /**
   * Returns the parsed OwnersConfig file for the given path if it exists.
   *
   * @param repository git repo
   * @param ownersPath path to OWNERS file in the git repo
   * @return config or null if it doesn't exist
   */
  private OwnersConfig getOwners(Repository repository, String ownersPath) {
    String owners = BlobUtils.getContent(repository, "master", ownersPath);

    if (owners != null) {
      ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
      try {
        return mapper.readValue(owners, OwnersConfig.class);
      } catch (IOException e) {
        log.warn("Invalid OWNERS file: {}", ownersPath, e);
        return null;
      }
    }

    return null;
  }

  /**
   * Translates emails to Account.Ids.
   * @param resolver account resolver
   * @param emails emails to translate
   * @return set of account ids
   */
  private Set<Account.Id> getOwnersFromEmails(AccountResolver resolver, Set<String> emails) {
    Set<Account.Id> result = new HashSet<Account.Id>();
    for (String email : emails) {
      try {
        Set<Account.Id> ids = resolver.findAll(email);
        result.addAll(ids);
      } catch (OrmException e) {
        throw new SystemException(e.getMessage());
      }
    }
    return result;
  }
}
