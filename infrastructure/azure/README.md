# Azure Deployment Guide

This directory contains Infrastructure-as-Code (Bicep) for the Azure side of the deployment, as a
complement to the existing `azure-pipelines.yml` at the repo root.

## What gets created

```
Resource Group (bobsresourcegroup)
├── Container Registry (Basic SKU)
├── App Service Plan (Linux, B1)
├── App Service (Linux container — runs the Docker image)
├── Key Vault (stores JWT secret + DB password)
└── [Optional] Azure Database for MySQL Flexible Server (Burstable B1ms)
```

If you set `createMySql=false` in the parameters, the Azure MySQL block is skipped and the App
Service points at an external DB (e.g. Aiven, which is your current setup).

---

## One-time prerequisites

1. **Azure subscription** with billing enabled
2. **Azure CLI** installed — `winget install Microsoft.AzureCLI` on Windows
3. `az login` — pops a browser to authenticate
4. `az account set --subscription "<your subscription name>"` if you have multiple

## Deploy with Bicep (one command)

```powershell
# Generate a strong JWT secret
$jwt = [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }))

# Copy the parameters template, fill in values, then:
az group create --name bobsresourcegroup --location centralus

az deployment group create `
  --resource-group bobsresourcegroup `
  --template-file infrastructure/azure/main.bicep `
  --parameters '@infrastructure/azure/main.parameters.json' `
  --parameters jwtSecret=$jwt
```

The deployment outputs the App Service URL, ACR login server name, and (if created) MySQL FQDN.

## Push the first image to ACR

```powershell
$acrName = az deployment group show -g bobsresourcegroup -n main --query properties.outputs.acrName.value -o tsv

# Authenticate Docker to ACR
az acr login --name $acrName

# Build and tag
docker build -t "$acrName.azurecr.io/securecapita:latest" .

# Push — App Service auto-pulls the new image (or use az webapp restart)
docker push "$acrName.azurecr.io/securecapita:latest"

az webapp restart --resource-group bobsresourcegroup --name <app-name-from-output>
```

---

## Two deployment styles, pick one

You now have **two ways** to deploy to Azure. Pick whichever fits your workflow:

| Method                                | When to use                                                                    |
|---------------------------------------|---------------------------------------------------------------------------------|
| **Azure DevOps Pipeline** (`azure-pipelines.yml`) | You want builds and deploys to happen in Azure DevOps (matches your existing setup) |
| **GitHub Actions** (`.github/workflows/azure-deploy.yml`) | You want everything in GitHub — repos, PRs, deploys |

Both push to the same ACR and update the same App Service. Don't run both for the same branch or
you'll have a deploy race.

---

## Bicep vs. ARM vs. Terraform — why Bicep?

| Tool             | Pros                                              | Cons                                            |
|------------------|---------------------------------------------------|--------------------------------------------------|
| **Bicep**        | Native to Azure; transpiles to ARM; concise syntax | Azure-only                                       |
| **ARM JSON**     | Universal; what Azure actually consumes            | Verbose, hard to read/maintain                   |
| **Terraform**    | Multi-cloud; large module ecosystem                | Another tool + state file to manage              |

For an Azure-only stack, Bicep is the lowest-friction path. It's authored by Microsoft and stays
ahead of new Azure features.

---

## Cost estimate

| Resource                    | Estimated monthly cost (Central US)         |
|-----------------------------|---------------------------------------------|
| App Service Plan B1         | ~$13/mo                                     |
| Container Registry (Basic)  | ~$5/mo (first 10 GB included)               |
| Key Vault                   | Free (first 10K operations/mo)              |
| MySQL Flexible Server B1ms  | ~$25/mo (skip if you already have Aiven)    |
| **Total (no Azure MySQL)**  | **~$18/mo**                                 |
| **Total (with Azure MySQL)**| **~$43/mo**                                 |

You can scale the App Service Plan to **F1 (Free)** for zero cost, but F1 doesn't support
`always-on` so the container cold-starts on every request after idle.
