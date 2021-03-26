#!groovy

podTemplate(
  containers:[
    containerTemplate(name: 'jdk11',                image:'adoptopenjdk:11-jdk-openj9',   ttyEnabled:true, command:'cat'),
    containerTemplate(name: 'docker',               image:'docker:18',                    ttyEnabled:true, command:'cat')
  ],
  volumes: [
    hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
    hostPathVolume(hostPath: '/var/lib/jenkins/.gradledist', mountPath: '/root/.gradle')
  ])
{
  node(POD_LABEL) {

    // See https://www.jenkins.io/doc/pipeline/steps/pipeline-utility-steps/
    // https://www.jenkins.io/doc/pipeline/steps/
    // https://github.com/jenkinsci/nexus-artifact-uploader-plugin

    stage ('checkout') {
      checkout_details = checkout scm
      props = readProperties file: './service/gradle.properties'
      app_version = props.appVersion
      deploy_cfg = null;
      semantic_version_components = app_version.toString().split('\\.')
      is_snapshot = app_version.contains('SNAPSHOT')
      constructed_tag = "build-${props?.appVersion}-${checkout_details?.GIT_COMMIT?.take(12)}"
      println("Got props: asString:${props} appVersion:${props.appVersion}/${props['appVersion']}/${semantic_version_components}");
      sh 'echo branch:$BRANCH_NAME'
      sh 'echo commit:$checkout_details.GIT_COMMIT'
    }

    stage ('check') {
      container('jdk11') {
        echo 'Hello, JDK'
        sh 'java -version'
      }
    }

    stage ('build') {
      container('jdk11') {
        dir('service') {
          env.GIT_BRANCH=checkout_details.GIT_BRANCH
          env.GIT_COMMIT=checkout_details.GIT_COMMIT
          sh './gradlew --version'
          sh './gradlew --no-daemon -x integrationTest --console=plain clean build'
          sh 'ls ./build/libs'
        }
      }
    }

    // https://www.jenkins.io/doc/book/pipeline/docker/
    stage('Build Docker Image') {
      container('docker') {
        //sh 'ls service/build/libs'
        docker_image = docker.build("knowledgeintegration/mod-directory")
      }
    }

    stage('Publish Docker Image') {
      container('docker') {
        dir('docker') {
          if ( checkout_details?.GIT_BRANCH == 'origin/master' ) {
            println("Considering build tag : ${constructed_tag} version:${props.appVersion}");
            // Some interesting stuff here https://github.com/jenkinsci/pipeline-examples/pull/83/files
            if ( !is_snapshot ) {
              // do_k8s_update=true
              docker.withRegistry('https://docker.libsdev.k-int.com','libsdev-deployer') {
                println("Publishing released version with latest tag and semver ${semantic_version_components}");
                docker_image.push('latest')
                docker_image.push("v${app_version}".toString())
                docker_image.push("v${semantic_version_components[0]}.${semantic_version_components[1]}".toString())
                docker_image.push("v${semantic_version_components[0]}".toString())
                // deploy_cfg='deploy_latest.yaml'
              }
              env.MOD_DIRECTORY_IMAGE="knowledgeintegration/mod-directory:${app_versionapp_version}"
              env.MOD_DIRECTORY_DEPLOY_AS="mod-directory-${app_version}".replaceAll('\\.','-').toLowerCase()
            }
            else {
              docker.withRegistry('https://docker.libsdev.k-int.com','libsdev-deployer') {
                println("Publishing snapshot-latest");
                docker_image.push('snapshot-latest')
                docker_image.push("${app_version}.${BUILD_NUMBER}".toString())
                // deploy_cfg='deploy_snapshot.yaml'
              }
              env.MOD_DIRECTORY_IMAGE="knowledgeintegration/mod-directory:${app_version}.${BUILD_NUMBER}"
              env.MOD_DIRECTORY_DEPLOY_AS="mod-directory-${app_version}.${BUILD_NUMBER}".replaceAll('\\.','-').toLowerCase();
            }
          }
          else {
            println("Not publishing docker image for branch ${checkout_details?.GIT_BRANCH}. Please merge to master for a docker image build");
          }
        }
      }
    }

    stage ('deploy') {
      println("Attempt deployment : ${env.MOD_DIRECTORY_IMAGE} as ${env.MOD_DIRECTORY_DEPLOY_AS}");
      kubernetesDeploy(
        enableConfigSubstitution: true,
        kubeconfigId: 'local_k8s',
        configs: 'scripts/k8s_deployment_template.yaml'
      );
      println("Wait for module to start...")
      sleep(10)
      println("Continue");
    }

    stage('Publish module descriptor') {
      sh 'ls -la service/build/resources/main/okapi'
      // this worked as expected
      // sh "curl http://okapi.reshare:9130/_/discovery/modules"
      sh "curl -XPOST 'http://okapi.reshare:9130/_/proxy/modules' -d @service/build/resources/main/okapi/ModuleDescriptor.json"

      // Now deployment descriptor
      // curl -XPOST 'http://localhost:9130/_/discovery/modules' -d "$DEP_DESC"
      // DEP_DESC == { "srvcId": "mod-directory-2.0.0-SNAPSHOT.201", "instId": "cluster-mod-directory-2.0.0-SNAPSHOT.201", "url": "http://10.0.2.2:8080/" }
      DEP_DESC="""{ "srvcId": "${env.MOD_DIRECTORY_DEPLOY_AS}", "instId": "${env.MOD_DIRECTORY_DEPLOY_AS}-cluster", "url": "http://${env.MOD_DIRECTORY_DEPLOY_AS}.reshare:8080/" } """
      deployment_command="curl -XPOST 'http://okapi.reshare:9130/_/discovery/modules' -d \"${DEP_DESC}\""
      println("Deployment descriptor will be ${DEP_DESC}");
      println("Deployment command will be ${deployment_command}");
      sh deployment_command


      tenants_to_update=['kint1']

      // now install for tenant
      ENABLE_DOC="""{ "id":"${env.MOD_DIRECTORY_DEPLOY_AS}", "action:"enable" }"""
      println("install doc will be ${DEP_DESC}");
      tenants_to_update.each { tenant ->
        println("Attempting module activation of ${env.MOD_DIRECTORY_DEPLOY_AS} on ${tenant} using ${DEP_DESC}");
        activation_command = "curl -XPOST 'http://localhost:9130/_/proxy/tenants/$tenant/install?tenantParameters=loadSample%3Dtest,loadReference%3Dother' -d \"$ENABLE_DOC\""
        println("Activation cmd: ${activation_command}");
        sh activation_command
      }

    }

  }

  stage ('Remove old builds') {
    //keep 3 builds per branch
    properties([[$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '3', numToKeepStr: '3']]]);
  }

}
