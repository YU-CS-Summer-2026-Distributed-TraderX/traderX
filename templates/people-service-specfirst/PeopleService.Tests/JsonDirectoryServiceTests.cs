using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.FileProviders;
using PeopleService.WebApi.Directory;
using Xunit;

namespace PeopleService.Tests;

// Unit tests for the JSON-backed directory. Rather than depend on the bundled MockDirectory file's
// location under a test's working dir, each test writes a small known roster to a temp file and
// points the service at its absolute path. Covers the lookup rules the controller leans on: logonId
// preferred over employeeId, employeeId fallback, substring match + take cap, and the constructor's
// loud failure when the configured file is missing (a silent empty directory would validate nobody).
public sealed class JsonDirectoryServiceTests : IDisposable
{
    private readonly string _file;

    public JsonDirectoryServiceTests()
    {
        _file = Path.Combine(Path.GetTempPath(), $"people-{Guid.NewGuid():N}.json");
        File.WriteAllText(_file, """
        [
          { "LogonId": "user01", "FullName": "Alice Johnson", "EmployeeId": "E0001" },
          { "LogonId": "user02", "FullName": "Bob Smith",     "EmployeeId": "E0002" },
          { "LogonId": "carol",  "FullName": "Carol Brown",   "EmployeeId": "E0003" }
        ]
        """);
    }

    public void Dispose() => File.Delete(_file);

    private JsonDirectoryService ServiceForFile(string path)
    {
        var config = new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?> { ["PeopleJsonFilePath"] = path })
            .Build();
        return new JsonDirectoryService(config, new StubEnvironment());
    }

    [Fact]
    public async Task GetPerson_byLogonId_returnsThatPerson()
    {
        var person = await ServiceForFile(_file).GetPersonAsync("user01", null);
        Assert.Equal("Alice Johnson", person?.FullName);
    }

    [Fact]
    public async Task GetPerson_fallsBackToEmployeeId_whenNoLogonId()
    {
        var person = await ServiceForFile(_file).GetPersonAsync(null, "E0002");
        Assert.Equal("user02", person?.LogonId);
    }

    [Fact]
    public async Task GetPerson_unknown_returnsNull()
    {
        Assert.Null(await ServiceForFile(_file).GetPersonAsync("nobody", null));
    }

    [Fact]
    public async Task ValidatePerson_reflectsExistence()
    {
        var service = ServiceForFile(_file);
        Assert.True(await service.ValidatePersonAsync("user01", null));
        Assert.False(await service.ValidatePersonAsync("nobody", null));
    }

    [Fact]
    public async Task GetMatchingPeople_matchesSubstringAndHonoursTake()
    {
        var matches = await ServiceForFile(_file).GetMatchingPeopleAsync("user", 2);
        Assert.Equal(2, matches.Count);
        Assert.All(matches, p => Assert.Contains("user", p.LogonId, StringComparison.Ordinal));
    }

    [Fact]
    public void Constructor_throwsFileNotFound_whenConfiguredFileMissing()
    {
        var missing = Path.Combine(Path.GetTempPath(), $"missing-{Guid.NewGuid():N}.json");
        Assert.Throws<FileNotFoundException>(() => ServiceForFile(missing));
    }

    private sealed class StubEnvironment : IWebHostEnvironment
    {
        // Never dereferenced by the service under test (it uses rooted absolute paths), but the
        // constructor signature requires an instance.
        public string ApplicationName { get; set; } = "tests";
        public string EnvironmentName { get; set; } = "Test";
        public string ContentRootPath { get; set; } = Path.GetTempPath();
        public string WebRootPath { get; set; } = Path.GetTempPath();
        public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
        public IFileProvider WebRootFileProvider { get; set; } = new NullFileProvider();
    }
}
