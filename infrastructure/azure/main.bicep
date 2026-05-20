// ─────────────────────────────────────────────────────────────────────────────
// SecureCapita full-stack app on Azure — declarative Bicep template.
//
// Provisions:
//   • Azure Container Registry (ACR) for the Docker image
//   • App Service Plan (Linux, B1) — cheapest plan that supports always-on
//   • App Service (Linux, container) — runs the image, port 8080
//   • Key Vault — stores JWT secret + DB password
//   • Optional: Azure Database for MySQL Flexible Server (set createMySql=true)
//
// Deploy with:
//   az group create --name bobsresourcegroup --location centralus
//   az deployment group create \
//     --resource-group bobsresourcegroup \
//     --template-file infrastructure/azure/main.bicep \
//     --parameters @infrastructure/azure/main.parameters.example.json
//
// See infrastructure/azure/README.md for the full walkthrough.
// ─────────────────────────────────────────────────────────────────────────────

@description('Prefix for all resource names. Lowercase, no spaces, 3-15 chars.')
@minLength(3)
@maxLength(15)
param appName string = 'securecapita'

@description('Azure region for all resources.')
param location string = resourceGroup().location

@description('App Service Plan SKU. B1 = Basic 1 core / 1.75GB RAM, always-on capable.')
@allowed(['B1', 'B2', 'S1', 'P1v3', 'P2v3'])
param appServicePlanSku string = 'B1'

@description('Whether to create an Azure Database for MySQL Flexible Server. Set false if using Aiven or other managed MySQL.')
param createMySql bool = false

@description('MySQL admin username (only used if createMySql=true).')
param mySqlAdminUsername string = 'mysqladmin'

@description('MySQL admin password (only used if createMySql=true). 8+ chars, mixed case, number, symbol.')
@secure()
param mySqlAdminPassword string = ''

@description('External JDBC URL — used only when createMySql=false (Aiven, RDS, etc.).')
param externalDatasourceUrl string = ''

@description('External DB username — used only when createMySql=false.')
param externalDatasourceUsername string = ''

@description('External DB password — used only when createMySql=false.')
@secure()
param externalDatasourcePassword string = ''

@description('JWT signing secret. 48+ random bytes.')
@secure()
param jwtSecret string

// ─────────────────────────────────────────────────────────────────────────────
// Resources
// ─────────────────────────────────────────────────────────────────────────────

// Azure Container Registry
resource acr 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: '${appName}acr${uniqueString(resourceGroup().id)}'
  location: location
  sku: {
    name: 'Basic' // cheapest tier, fine for single-app deploys
  }
  properties: {
    adminUserEnabled: true // App Service uses these creds to pull the image
  }
}

// App Service Plan (Linux)
resource appServicePlan 'Microsoft.Web/serverfarms@2023-12-01' = {
  name: '${appName}-plan'
  location: location
  sku: {
    name: appServicePlanSku
  }
  kind: 'linux'
  properties: {
    reserved: true // required for Linux plans
  }
}

// Key Vault for secrets
resource keyVault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: '${appName}-kv-${uniqueString(resourceGroup().id)}'
  location: location
  properties: {
    sku: {
      family: 'A'
      name: 'standard'
    }
    tenantId: subscription().tenantId
    enableRbacAuthorization: true
    enabledForTemplateDeployment: true
    softDeleteRetentionInDays: 7
  }
}

resource jwtSecretKv 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: keyVault
  name: 'jwt-secret'
  properties: {
    value: jwtSecret
  }
}

// Optional: Azure Database for MySQL Flexible Server
resource mySqlServer 'Microsoft.DBforMySQL/flexibleServers@2023-12-30' = if (createMySql) {
  name: '${appName}-mysql-${uniqueString(resourceGroup().id)}'
  location: location
  sku: {
    name: 'Standard_B1ms' // burstable, cheapest tier
    tier: 'Burstable'
  }
  properties: {
    administratorLogin: mySqlAdminUsername
    administratorLoginPassword: mySqlAdminPassword
    version: '8.0.21'
    storage: {
      storageSizeGB: 20
      autoGrow: 'Enabled'
    }
    backup: {
      backupRetentionDays: 7
      geoRedundantBackup: 'Disabled'
    }
    highAvailability: {
      mode: 'Disabled'
    }
  }
}

resource mySqlDatabase 'Microsoft.DBforMySQL/flexibleServers/databases@2023-12-30' = if (createMySql) {
  parent: mySqlServer
  name: 'db2'
  properties: {
    charset: 'utf8mb4'
    collation: 'utf8mb4_unicode_ci'
  }
}

resource mySqlFirewallAllowAzure 'Microsoft.DBforMySQL/flexibleServers/firewallRules@2023-12-30' = if (createMySql) {
  parent: mySqlServer
  name: 'AllowAllAzureServicesAndResourcesWithinAzureIps'
  properties: {
    startIpAddress: '0.0.0.0'
    endIpAddress: '0.0.0.0'
  }
}

resource mySqlPasswordKv 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = if (createMySql) {
  parent: keyVault
  name: 'mysql-admin-password'
  properties: {
    value: mySqlAdminPassword
  }
}

// Computed datasource URL — points at either the new MySQL or the external one
var datasourceUrl = createMySql
  ? 'jdbc:mysql://${mySqlServer.properties.fullyQualifiedDomainName}:3306/db2?useSSL=true&requireSSL=true'
  : externalDatasourceUrl
var datasourceUsername = createMySql ? '${mySqlAdminUsername}' : externalDatasourceUsername
var datasourcePassword = createMySql ? mySqlAdminPassword : externalDatasourcePassword

// App Service (containerized)
resource webApp 'Microsoft.Web/sites@2023-12-01' = {
  name: '${appName}-app-${uniqueString(resourceGroup().id)}'
  location: location
  kind: 'app,linux,container'
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    serverFarmId: appServicePlan.id
    httpsOnly: true
    siteConfig: {
      linuxFxVersion: 'DOCKER|${acr.properties.loginServer}/${appName}:latest'
      acrUseManagedIdentityCreds: false
      alwaysOn: true
      healthCheckPath: '/actuator/health'
      appSettings: [
        {
          name: 'WEBSITES_PORT'
          value: '8080' // tells App Service which port the container listens on
        }
        {
          name: 'DOCKER_REGISTRY_SERVER_URL'
          value: 'https://${acr.properties.loginServer}'
        }
        {
          name: 'DOCKER_REGISTRY_SERVER_USERNAME'
          value: acr.listCredentials().username
        }
        {
          name: 'DOCKER_REGISTRY_SERVER_PASSWORD'
          value: acr.listCredentials().passwords[0].value
        }
        {
          name: 'SPRING_PROFILES_ACTIVE'
          value: 'prod'
        }
        {
          name: 'SPRING_DATASOURCE_URL'
          value: datasourceUrl
        }
        {
          name: 'SPRING_DATASOURCE_USERNAME'
          value: datasourceUsername
        }
        {
          name: 'SPRING_DATASOURCE_PASSWORD'
          value: datasourcePassword
        }
        {
          name: 'JWT_SECRET'
          value: '@Microsoft.KeyVault(VaultName=${keyVault.name};SecretName=jwt-secret)'
        }
      ]
    }
  }
}

// Grant the App Service's managed identity Read access to the Key Vault
// so the @Microsoft.KeyVault(...) reference in JWT_SECRET resolves.
resource kvSecretsUserRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(keyVault.id, webApp.id, 'Key Vault Secrets User')
  scope: keyVault
  properties: {
    // Built-in role: "Key Vault Secrets User" — reads secret values.
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4633458b-17de-408a-b874-0445c86b69e6')
    principalId: webApp.identity.principalId
    principalType: 'ServicePrincipal'
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Outputs
// ─────────────────────────────────────────────────────────────────────────────
output appUrl string = 'https://${webApp.properties.defaultHostName}'
output acrLoginServer string = acr.properties.loginServer
output acrName string = acr.name
output keyVaultName string = keyVault.name
output mySqlFqdn string = createMySql ? mySqlServer.properties.fullyQualifiedDomainName : 'n/a (external DB)'
