package org.olf.reshare

import org.olf.okapi.modules.directory.DirectoryEntry

import grails.events.annotation.Subscriber
import grails.gorm.multitenancy.Tenants
import grails.gorm.transactions.Transactional
import grails.web.databinding.DataBinder
import groovyx.net.http.ContentType
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.Method

/**
 *
 */
@Transactional
class FoafService implements DataBinder {

  private static long MIN_READ_INTERVAL = 60 * 60 * 24 * 7 * 1000; // 7 days between directory reads


  @Subscriber('okapi:dataload:sample')
  public void afterSampleLoaded (final String tenantId, final String value, final boolean existing_tenant, final boolean upgrading, final String toVersion, final String fromVersion) {
    log.debug("sample data loaded....");
    // See if we can find the URL of our seed entry in the directory
    Tenants.withId(tenantId+'_mod_directory') {
      checkFriend('https://raw.githubusercontent.com/openlibraryenvironment/mod-directory/master/seed_data/olf.json');
    }
  }


  /**
   * Check a URL to see if it needs to be visited.
   */
  public void checkFriend(String url, int depth = 0) {

    log.debug("checkFriend(${url}, ${depth})");

    if ( ( url != null ) &&
         ( url.length() > 5 ) &&
         ( url.toLowerCase().startsWith('http') ) && 
         ( depth < 4 ) ) {
      if ( shouldVisit(url) ) {
        log.debug("Visiting....${url}");
        processFriend(url, depth);
      }
    }
  }

  // return true if we should attempt to visit this URL - currently
  // returns false if we already know about this URL and have recently visited it
  private boolean shouldVisit(String url) {
    boolean result = false;
    DirectoryEntry de = DirectoryEntry.findByFoafUrl(url)
    if ( de != null ) {
      if ( ( de.foafTimestamp == null ) || ( System.currentTimeMillis() - de.foafTimestamp > MIN_READ_INTERVAL ) ) {
        // We know this FOAF URL before but it has never been visited, or it 
        // was more than MIN_READ_INTERVAL ms ago, so lets reread.
        log.debug("No directory entry found for foaf URL ${url} and timestamp expired");
        result = true;
      }
    }
    else {
      // We have not seen this URL before (At some point, we should probably have a blacklist to check)
      log.debug("No directory entry found for foaf URL ${url}");
      result = true;
    }

    return result;
  }

  private void processFriend(String url, int depth=0) {
    try {
      def http = new HTTPBuilder(url)
      //http.auth.basic ('username','password')
      http.request(Method.GET, ContentType.JSON) {
        headers.'Content-Type' = 'application/json'
        response.success = { resp, json ->
          log.debug("Got json response ${json}");


          // Make sure that the JSON really is an array of foaf descriptions
          if ( validateJson(json) ) {

            // Look up the directory entry for the root
            DirectoryEntry de = DirectoryEntry.findByFoafUrlOrSlug(url, json.slug)

            // Remove the friends list - we will process it later on
            Object friends_list = json.remove('friends')
            Object announcements = json.remove('announcements')

            log.debug("Result of DirectoryEntry.findByFoafUrlOrSlug(${url},${json.slug}) : ${de}")
            if ( de == null ) {
              log.debug("Create a new directory entry(foafUrl:${url}, name:${json.name})")
              de = new DirectoryEntry(foafUrl:url, name: json.name)
            }
            else {
              log.debug("DE already exists");
            }

            // Load the json over the domain object
            log.debug("About to call doBind(${de},${json})")
            
            bindData (de, json)

            // update the touched timestamp
            de.foafTimestamp = System.currentTimeMillis();

            // save
            log.debug("Saving...");
            de.save(flush:true, failOnError:true);

            // Process any friends
            log.debug("Processing friends ${friends_list}");
            if ( friends_list ) {
              friends_list.each { fr ->
                if ( fr.foaf ) {
                  checkFriend(fr.foaf, depth+1);
                }
              }
            }
          }
        }
        response.failure = { json ->
          log.warn("Problem processing FOAF URL ${url}");
        }
      }
    }
    catch ( Exception e ) {
      log.error("Problem trying to process FOAF URL ${url} : ${e.message}",e)
    }
    finally {
      log.debug("leaving processFriend(${url},${depth}");
    }
  }

  private boolean validateJson(json) {
    boolean result = false;
    if ( ( json.name != null ) &&
         ( json.name.length() > 0 ) ) {
      result = true;
    }
    return result;
  }

  // return true if record updated
  private boolean updateFromJson(DirectoryEntry de, Map json) {
    boolean result = false

    // do update
    result &= mergeField('name', 'name', de, json);
    // result &= mergeField('url', 'url', de, json);
    result &= mergeField('description', 'description', de, json);

    // Normally immutable
    // result &= mergeField('slug', 'slug', de, json);

    return result;
  }

  /**
   * Check to see if fieldname is present in json, and if so, compare it to the current value of that field in the object
   * if different, set that field on the object and return true to signify that the record was updated.
   */
  private boolean mergeField(String jsonField, String domainModelField, Object obj, Map json) {
    boolean result = false;
    if ( json[jsonField] != null ) {
      obj[domainModelField] = json[jsonField]
      result = true;
    }
    return result;
  }
}
