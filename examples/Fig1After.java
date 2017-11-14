class Test {
    public void test() {
        Set<TaskResult> results = new HashSet<>();
        for (Task t : tasks) {
            t.execute();
            results.add(t.getResult());
        }
    }
}
